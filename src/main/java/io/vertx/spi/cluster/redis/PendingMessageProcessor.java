/*
 * Copyright (c) 2018 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package io.vertx.spi.cluster.redis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.impl.clustered.ClusterNodeInfo;
import io.vertx.core.eventbus.impl.clustered.ClusteredEventBus;
import io.vertx.core.eventbus.impl.clustered.ClusteredMessage;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.impl.ServerID;
import io.vertx.core.spi.cluster.AsyncMultiMap;
import io.vertx.core.spi.cluster.ChoosableIterable;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.spi.cluster.redis.NonPublicAPI.ClusteredEventBusAPI;
import io.vertx.spi.cluster.redis.NonPublicAPI.ClusteredEventBusAPI.ConnectionHolderAPI;

/**
 * Tryable to choose another server ID
 * 
 * @author <a href="mailto:leo.tu.taipei@gmail.com">Leo Tu</a>
 */
public class PendingMessageProcessor {
	private static final Logger log = LoggerFactory.getLogger(PendingMessageProcessor.class);

	final static private String HA_ORIGINAL_SERVER_ID_KEY = "_HA_ORIGINAL_SERVER_ID";
	final static private String HA_RESEND_SERVER_ID_KEY = "_HA_RESEND_SERVER_ID";
	final static private String HA_RESEND_AGAIN_SERVER_ID_KEY = "_HA_RESEND_AGAIN_SERVER_ID";

	private boolean debug = false;

	private final Vertx vertx;
	private final ClusteredEventBus eventBus;
	@SuppressWarnings("unused")
	private final Context sendNoContext;
	private ServerID selfServerID; // self, local server

	private final AsyncMultiMap<String, ClusterNodeInfo> subs;
	private final ClusterManager clusterManager;
	private final ConcurrentMap<ServerID, Object> connections; // <ServerID, ConnectionHolder>

	public PendingMessageProcessor(Vertx vertx, ClusterManager clusterManager, ClusteredEventBus eventBus,
			AsyncMultiMap<String, ClusterNodeInfo> subs, ConcurrentMap<ServerID, Object> connections) {
		this.vertx = vertx;
		this.clusterManager = clusterManager;
		this.eventBus = eventBus;
		this.subs = subs;
		this.sendNoContext = vertx.getOrCreateContext();
		this.connections = connections;
	}

	/**
	 * 
	 * @param serverID failedServerID
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public Future<Void> run(ServerID failedServerID, Queue<ClusteredMessage<?, ?>> pending) {
		if (this.selfServerID == null) {
			this.selfServerID = ClusteredEventBusAPI.serverID(this.eventBus);
		}
		Objects.requireNonNull(failedServerID, "failedServerID");
		Objects.requireNonNull(pending, "pending");
		if (pending.isEmpty()) {
			if (debug) {
				log.debug("(pending.isEmpty()), failedServerID: {}", failedServerID);
			}
			return Future.succeededFuture();
		}

		int pendingSize = pending.size();
		Queue<Future> runStatusFutures = new ArrayDeque<>();
		for (int i = 0; i < pendingSize; i++) {
			runStatusFutures.add(Future.future());
		}
		List<Future> list = new ArrayList<>(runStatusFutures);

		Future<Void> finish = Future.future();
		vertx.executeBlocking(future -> {
			ClusteredMessage<?, ?> cmessage;
			while ((cmessage = pending.poll()) != null) { // FIFO
				Future<Integer> runStatusFuture = runStatusFutures.poll();
				final ClusteredMessage<?, ?> message = cmessage;
				if (!clusterManager.isActive()) {
					if (debug) {
						log.debug("(!clusterManager.isActive())");
					}
					pending.clear();
					//
					runStatusFuture.complete(0);
					Future<Integer> f;
					while ((f = runStatusFutures.poll()) != null) {
						f.complete(0);
					}
					break;
				} else if (!discard(message)) {
					resend(failedServerID, message).setHandler(ar -> {
						if (ar.failed()) {
							log.debug(
									"failed {} to retry {} message, address: {}, replyAddress:{}, isSend:{}, isFromWire:{}, error: {}",
									message.isSend() ? "send" : "publish", failedServerID, message.address(), message.replyAddress(),
									message.isFromWire(), ar.cause().toString());
							runStatusFuture.fail(ar.cause());
						} else {
							if (!ar.result()) {
								log.debug(
										"failed {} to retry {} message, address: {}, replyAddress:{}, isSend:{}, isFromWire:{}, no available serverID.",
										message.isSend() ? "send" : "publish", failedServerID, message.address(), message.replyAddress(),
										message.isFromWire());
							}
							runStatusFuture.complete(ar.result() ? 1 : 0);
						}
					});
				} else {
					log.debug("discard {} to retry {} message, address: {}, replyAddress:{}, isSend:{}, isFromWire:{}",
							message.isSend() ? "send" : "publish", failedServerID, message.address(), message.replyAddress(),
							message.isFromWire());
					runStatusFuture.complete(-1);
				}
			} // while

			complete(pendingSize, list, future);
		}, finish);
		return finish;
	}

	@SuppressWarnings("rawtypes")
	private void complete(int pendingSize, List<Future> list, Future future) {
		CompositeFuture.join(list).setHandler(ar -> {
			// if (ar.failed()) { // All completed and at least one faileds
			// } else { // All succeeded
			// }
			int failedCounter = 0;
			int discardCounter = 0;
			int sureSendedCounter = 0;
			int notSendedCounter = 0;
			for (Future f : list) {
				if (f.failed()) {
					failedCounter++;
				} else {
					int status = (Integer) f.result();
					if (status == 1) {
						sureSendedCounter++;
					} else if (status == 0) {
						notSendedCounter++;
					} else {
						discardCounter++;
					}
				}
			}
			if (debug) {
				if (pendingSize != (failedCounter + discardCounter + sureSendedCounter + notSendedCounter)) {
					log.debug(
							"messages pendingSize: {}, discardCounter: {}, failedCounter: {}, sureSendedCounter: {}, notSendedCounter: {}",
							pendingSize, discardCounter, failedCounter, sureSendedCounter, notSendedCounter);
				}
			}
			future.complete();
		});
	}

	private boolean discard(ClusteredMessage<?, ?> message) {
		if (!message.isSend()) { // skip Publish
			return true;
		}
		if (message.isFromWire()) { // skip readFromWire
			if (debug) {
				log.debug("(message.isFromWire())");
			}
			return true;
		}

		String haOriginalServerId = message.headers().get(HA_ORIGINAL_SERVER_ID_KEY);
		String haResendServerId = message.headers().get(HA_RESEND_SERVER_ID_KEY);
		String haResendAgainServerId = message.headers().get(HA_RESEND_AGAIN_SERVER_ID_KEY);

		if (haResendAgainServerId != null) {
			if (debug) {
				log.debug(
						"discard(haResendAgainServerId != null): haResendAgainServerId: {}, haResendServerId: {}, haResendAgainServerId: {}, address: {}",
						haResendAgainServerId, haResendServerId, haResendAgainServerId, message.address());
			}
			return true; // had retry 2 times
		}
		if (haOriginalServerId != null && haResendServerId != null && haOriginalServerId.equals(haResendServerId)) {
			if (debug) {
				log.debug(
						"discard(haOriginalServerId.equals(haResendServerId)): haResendAgainServerId: {}, haResendServerId: {}, haResendAgainServerId: {}, address: {}",
						haResendAgainServerId, haResendServerId, haResendAgainServerId, message.address());
			}
			return true; // had retry original server
		}
		return false;
	}

	private Future<Boolean> resend(ServerID failedServerID, ClusteredMessage<?, ?> message) {
		String address = message.address();
		Future<Boolean> fu = Future.future();
		Handler<AsyncResult<ChoosableIterable<ClusterNodeInfo>>> resultHandler = asyncResult -> {
			if (asyncResult.succeeded()) {
				ChoosableIterable<ClusterNodeInfo> serverIDs = asyncResult.result();
				if (serverIDs != null && !serverIDs.isEmpty()) {
					resendToSubs(failedServerID, message, serverIDs).setHandler(ar -> {
						if (ar.failed()) {
							fu.fail(ar.cause());
						} else {
							fu.complete(true);
						}
					});
				} else {
					log.debug("No available serverID by address: {}, failed server id: {}, error: {}", address, failedServerID);
					fu.complete(false);
				}
			} else {
				// log.warn("Address: {}, failed to resend message, failed server id: {}, error: {}", address, failedServerID,
				// asyncResult.cause().toString());
				fu.fail(asyncResult.cause());
			}
		};
		// if (Vertx.currentContext() == null) {
		// Guarantees the order when there is no current context ?
		// sendNoContext.runOnContext(v -> {
		subs.get(address, resultHandler);
		// });
		// } else {
		// subs.get(address, resultHandler);
		// }
		return fu;
	}

	/**
	 * Choose new one
	 * 
	 * @param originalServerID failed server
	 * @see io.vertx.spi.cluster.redis.impl.RedisChoosableSet
	 */
	private Future<Void> resendToSubs(ServerID failedServerID, ClusteredMessage<?, ?> message,
			ChoosableIterable<ClusterNodeInfo> subs) {
		Future<Void> fu = Future.future();
		vertx.executeBlocking(future -> {
			ClusterNodeInfo ci = subs.choose();
			ServerID choosedServerID = null;
			ServerID pendingServerID = null;
			ServerID localServerID = null;
			while ((ci = subs.choose()) != null) {
				ServerID nextId = ci.serverID;
				if (!nextId.equals(failedServerID) && !nextId.equals(selfServerID)) {
					Object connHolder = connections.get(nextId);
					if (connHolder == null) { // new open
						choosedServerID = nextId;
						break;
					} else { // existing
						Queue<ClusteredMessage<?, ?>> pending = ConnectionHolderAPI.pending(connHolder);
						ServerID holderServerID = ConnectionHolderAPI.serverID(connHolder);
						if (!nextId.equals(holderServerID)) {
							throw new RuntimeException(
									"(!nextId.equals(holderServerID), nextId: " + nextId + ", holderServerID: " + holderServerID);
						}
						if (pending == null || pending.isEmpty()) { // not pending node
							choosedServerID = nextId;
							break;
						} else if (pending != null) {
							pendingServerID = nextId; // nextId is pending node
						}
					}
				} else if (nextId.equals(selfServerID)) {
					if (localServerID != null && localServerID.equals(nextId)) {
						// log.debug(
						// "(localServerID != null && localServerID.equals(nextId)), nextId: {}, failedServerID: {}, selfServerID:
						// {}",
						// nextId, failedServerID, selfServerID);
						break;
					} else {
						localServerID = nextId;
					}
				}

			}

			if (choosedServerID == null) { // not found available server ID
				choosedServerID = pendingServerID != null ? pendingServerID : failedServerID;
				if (choosedServerID.equals(failedServerID) && localServerID != null) {
					choosedServerID = localServerID; // change to localServerID ?
					if (debug) {
						log.debug("new one not found, change to local server: {}, address: '{}'", choosedServerID,
								message.address());
					}
				} else {
					if (choosedServerID.equals(failedServerID)) {
						if (debug) {
							log.debug("new one not found, return to failed server: {}, address: '{}'", choosedServerID,
									message.address());
						}
					} else {
						if (debug) {
							log.debug("new one not found, change to pending server: {}, address: '{}'", choosedServerID,
									message.address());
						}
					}
				}
			}
			// else {
			// if (debug) {
			// log.debug("switch to new server: {}, previous failed server: {}, address: '{}'", choosedServerID,
			// failedServerID, message.address());
			// }
			// }

			String originalServerId = message.headers().get(HA_ORIGINAL_SERVER_ID_KEY);
			if (originalServerId == null) {
				message.headers().set(HA_ORIGINAL_SERVER_ID_KEY, failedServerID.toString());
				message.headers().set(HA_RESEND_SERVER_ID_KEY, choosedServerID.toString());
			} else {
				message.headers().set(HA_RESEND_SERVER_ID_KEY, failedServerID.toString());
				message.headers().set(HA_RESEND_AGAIN_SERVER_ID_KEY, choosedServerID.toString());
			}

			ClusteredEventBusAPI.sendRemote(eventBus, choosedServerID, message);
			future.complete();
		}, fu);
		return fu;
	}

	static public boolean isRetryMessage(Message<?> message) {
		MultiMap headers = message.headers();
		String haResendServerId = headers.get(HA_RESEND_SERVER_ID_KEY);
		String haResendAgainServerId = headers.get(HA_RESEND_AGAIN_SERVER_ID_KEY);
		return haResendServerId != null || haResendAgainServerId != null;
	}

}
