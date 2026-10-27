package com.graalmail.p2p;

import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.blockchain.NotificationRequest;
import com.graalmail.p2p.blockchain.PolygonNotificationService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.blockchain.DepositPolicy;
import com.graalmail.p2p.delivery.DeliveryStore;
import com.graalmail.p2p.delivery.MessageRecord;
import com.graalmail.p2p.delivery.OutgoingEntry;
import com.graalmail.p2p.identity.IdentityService;
import com.graalmail.p2p.mailbox.InboxRepository;
import com.graalmail.p2p.mailbox.StoragePolicy;
import com.graalmail.p2p.mailbox.StorageReceipt;
import com.graalmail.p2p.protocol.Envelope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class DefaultDeliveryCoordinator implements DeliveryCoordinator {
    private final IdentityService identityService;
    private final EncryptionService crypto;
    private final PeerNetworkService peerNetwork;
    private final PolygonNotificationService polygon;
    private final InboxRepository inbox;
    private final List<PeerAddress> mailboxPeers;
    private final NetworkId networkId;
    private final String contractAddress;
    private final String senderWallet;
    private final StoragePolicy storagePolicy;
    private final RateLimiter sendRateLimiter;
    private final DeliveryStore deliveryStore;
    private volatile DepositPolicy depositPolicy = DepositPolicy.disabled();

    public DefaultDeliveryCoordinator(IdentityService identityService,
                                     EncryptionService crypto,
                                     PeerNetworkService peerNetwork,
                                     PolygonNotificationService polygon,
                                     InboxRepository inbox,
                                     List<PeerAddress> mailboxPeers,
                                     NetworkId networkId,
                                     String contractAddress,
                                     String senderWallet) {
        this(identityService, crypto, peerNetwork, polygon, inbox, mailboxPeers, networkId, contractAddress, senderWallet, 1);
    }

    public DefaultDeliveryCoordinator(IdentityService identityService,
                                     EncryptionService crypto,
                                     PeerNetworkService peerNetwork,
                                     PolygonNotificationService polygon,
                                     InboxRepository inbox,
                                     List<PeerAddress> mailboxPeers,
                                     NetworkId networkId,
                                     String contractAddress,
                                     String senderWallet,
                                     int requiredReplicas) {
        this(identityService, crypto, peerNetwork, polygon, inbox, mailboxPeers, networkId,
                contractAddress, senderWallet, StoragePolicy.withReplicas(Math.max(1, requiredReplicas)),
                new RateLimiter(60, 60_000));
    }

    public DefaultDeliveryCoordinator(IdentityService identityService,
                                     EncryptionService crypto,
                                     PeerNetworkService peerNetwork,
                                     PolygonNotificationService polygon,
                                     InboxRepository inbox,
                                     List<PeerAddress> mailboxPeers,
                                     NetworkId networkId,
                                     String contractAddress,
                                     String senderWallet,
                                     StoragePolicy storagePolicy,
                                     RateLimiter sendRateLimiter) {
        this(identityService, crypto, peerNetwork, polygon, inbox, mailboxPeers, networkId,
                contractAddress, senderWallet, storagePolicy, sendRateLimiter, null);
    }

    public DefaultDeliveryCoordinator(IdentityService identityService,
                                     EncryptionService crypto,
                                     PeerNetworkService peerNetwork,
                                     PolygonNotificationService polygon,
                                     InboxRepository inbox,
                                     List<PeerAddress> mailboxPeers,
                                     NetworkId networkId,
                                     String contractAddress,
                                     String senderWallet,
                                     StoragePolicy storagePolicy,
                                     RateLimiter sendRateLimiter,
                                     DeliveryStore deliveryStore) {
        this.identityService = identityService;
        this.crypto = crypto;
        this.peerNetwork = peerNetwork;
        this.polygon = polygon;
        this.inbox = inbox;
        this.mailboxPeers = new ArrayList<>(mailboxPeers);
        this.networkId = networkId;
        this.contractAddress = contractAddress;
        this.senderWallet = senderWallet;
        this.storagePolicy = storagePolicy;
        this.sendRateLimiter = sendRateLimiter;
        this.deliveryStore = deliveryStore;
    }

    @Override
    public CompletionStage<SendResult> send(SendRequest request) {
        try {
            depositPolicy.checkSend(request.depositTxProof());
        } catch (PolyMailException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (!sendRateLimiter.tryAcquire("send:" + request.senderAddress())) {
            return CompletableFuture.failedFuture(new PolyMailException(
                    com.graalmail.core.error.ErrorCode.RATE_LIMITED, "send rate limit exceeded"));
        }
        if (request.contentBytes() == null || request.contentBytes().length > storagePolicy.maxContentBytes()) {
            return CompletableFuture.failedFuture(new PolyMailException(
                    com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "content exceeds storage policy size"));
        }
        return identityService.resolve(request.recipientAddress()).thenCompose(identity -> {
            if (!identityService.verify(identity)) {
                return CompletableFuture.failedFuture(new PolyMailException(com.graalmail.core.error.ErrorCode.KEY_UNTRUSTED, "recipient identity untrusted"));
            }
            String messageId = "msg-" + java.util.UUID.randomUUID();
            long now = System.currentTimeMillis();
            long expiresAt = request.expiresAt() > 0 ? request.expiresAt() : now + ProtocolConstants.DEFAULT_RETENTION_SECONDS * 1000;

            byte[] aad = (messageId + "|" + request.senderAddress() + "|" + request.recipientAddress()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] cipherBytes;
            try {
                cipherBytes = crypto.encrypt(request.contentBytes(), identity.encryptionPublicKey(), aad);
            } catch (PolyMailException e) {
                return CompletableFuture.failedFuture(e);
            }

            Envelope env = new Envelope(ProtocolConstants.PROTOCOL_VERSION, networkId.code(), messageId, null,
                    cipherBytes, aad, request.senderKeyVersion(), identity.keyVersion(), now, expiresAt, null);
            byte[] hash = crypto.contentHash(env.unsignedContentBytes());
            Envelope withHash = new Envelope(ProtocolConstants.PROTOCOL_VERSION, networkId.code(), messageId, hash,
                    cipherBytes, aad, request.senderKeyVersion(), identity.keyVersion(), now, expiresAt, null);
            byte[] sig = crypto.sign(request.senderSigningKey(), withHash.signingBytes());
            Envelope finalEnv = new Envelope(ProtocolConstants.PROTOCOL_VERSION, networkId.code(), messageId, hash,
                    cipherBytes, aad, request.senderKeyVersion(), identity.keyVersion(), now, expiresAt, sig);

            // Durable outbox: persist the pending operation and message record before
            // touching the network so a crash never loses the send (spec section 10.1).
            String operationId = java.util.UUID.randomUUID().toString();
            byte[] envelopeBytes = finalEnv.toBytes();
            persistOutgoing(operationId, messageId, "UPLOADING", 0, now, "", envelopeBytes);
            persistMessage(new MessageRecord(messageId, "OUT", request.senderAddress(), request.recipientAddress(),
                    now, expiresAt, hex(hash), "", "QUEUED", "UNVERIFIED", ""));

            // Phase B: store content and count only cryptographically verified receipts
            // toward the quorum (spec sections 9.2, 10.2, 13.4). A receipt from an unknown
            // peer, or one whose signature does not verify, never counts.
            List<CompletableFuture<Boolean>> futures = new ArrayList<>();
            for (PeerAddress pa : mailboxPeers) {
                futures.add(peerNetwork.storeMessage(pa, finalEnv)
                        .thenCompose(receipt -> verifiedReceipt(pa, receipt).thenApply(ok -> {
                            if (deliveryStore != null) deliveryStore.saveReceiptRecord(receipt, ok);
                            return ok;
                        }))
                        .handle((ok, err) -> ok != null && ok)
                        .toCompletableFuture());
            }
            CompletionStage<Void> stored = CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                    .thenApply(v -> {
                        int verified = 0;
                        for (CompletableFuture<Boolean> f : futures) {
                            try {
                                if (Boolean.TRUE.equals(f.getNow(false))) verified++;
                            } catch (Exception ignored) {}
                        }
                        if (verified < storagePolicy.requiredReplicas() && !storagePolicy.bestEffortDelivery()) {
                            failOutgoing(operationId, messageId, "STORAGE_QUORUM_NOT_MET");
                            throw new PolyMailException(com.graalmail.core.error.ErrorCode.STORAGE_QUORUM_NOT_MET,
                                    "storage quorum not met: " + verified + "/" + storagePolicy.requiredReplicas() + " verified receipts");
                        }
                        updateOutgoing(operationId, "STORED", 0, now, "");
                        updateMessage(messageId, "STORED", "UNVERIFIED", "");
                        return null;
                    });
            // Phase C: publish the notification only after storage is confirmed (spec section 10.3).
            // Storage and notification errors stay distinct so callers can apply the right
            // recovery policy (spec sections 14.2-14.3).
            return stored.thenCompose(v -> {
                byte[] inboxTag = NotificationTag.generate(identity.encryptionPublicKey(), messageId);
                NotificationRequest nr = new NotificationRequest(networkId, contractAddress, messageId, hash, inboxTag, expiresAt, senderWallet);
                return polygon.publishNotification(nr)
                        .thenApply(tx -> {
                            updateOutgoing(operationId, "NOTIFICATION_SUBMITTED", 0, now, "");
                            if (deliveryStore != null) deliveryStore.updateMessageNotification(messageId, tx);
                            updateMessage(messageId, "NOTIFICATION_SUBMITTED", "UNVERIFIED", "");
                            return new SendResult(messageId, tx, DeliveryState.NOTIFICATION_SUBMITTED);
                        })
                        .exceptionallyCompose(err -> {
                            failOutgoing(operationId, messageId, errorCodeOf(err));
                            return CompletableFuture.failedFuture(err instanceof RuntimeException re ? re : new RuntimeException(err));
                        });
            });
        });
    }

    /**
     * Re-drives pending durable operations after a restart or outage (spec section 14).
     * Operations stuck in {@code UPLOADING}/{@code STORED} are re-stored and re-published
     * from the persisted envelope; {@code NOTIFICATION_SUBMITTED} operations are
     * reconciled against the chain. Returns the number of operations now confirmed.
     */
    /** Sets the anti-spam deposit policy; disabled by default (spec 5.5). */
    public void depositPolicy(DepositPolicy policy) {
        this.depositPolicy = policy == null ? DepositPolicy.disabled() : policy;
    }

    public CompletionStage<Integer> resumePending() {
        if (deliveryStore == null) return CompletableFuture.completedFuture(0);
        List<OutgoingEntry> pending = deliveryStore.pendingOutgoing();
        CompletionStage<Integer> acc = CompletableFuture.completedFuture(0);
        for (OutgoingEntry entry : pending) {
            acc = acc.thenCompose(n -> resumeOne(entry).thenApply(ok -> n + (ok ? 1 : 0)));
        }
        return acc;
    }

    private CompletionStage<Boolean> resumeOne(OutgoingEntry entry) {
        if ("NOTIFICATION_SUBMITTED".equals(entry.state())) {
            MessageRecord rec = deliveryStore.messageRecord(entry.messageId());
            String tx = rec == null ? "" : rec.notificationTxHash();
            if (tx == null || tx.isBlank()) return CompletableFuture.completedFuture(false);
            return polygon.track(tx).thenApply(status -> {
                if (status == com.graalmail.p2p.blockchain.TransactionStatus.CONFIRMED
                        || status == com.graalmail.p2p.blockchain.TransactionStatus.FINALIZED) {
                    deliveryStore.updateOutgoing(entry.operationId(), "NOTIFICATION_CONFIRMED",
                            entry.retryCount(), System.currentTimeMillis(), "");
                    updateMessage(entry.messageId(), "NOTIFICATION_CONFIRMED", "UNVERIFIED", "");
                    return true;
                }
                if (status == com.graalmail.p2p.blockchain.TransactionStatus.FAILED) {
                    failOutgoing(entry.operationId(), entry.messageId(), "TRANSACTION_REVERTED");
                }
                return false;
            }).exceptionally(err -> false);
        }
        if (entry.payload() == null) return CompletableFuture.completedFuture(false);
        Envelope env;
        try {
            env = Envelope.fromBytes(entry.payload());
        } catch (PolyMailException e) {
            failOutgoing(entry.operationId(), entry.messageId(), "INVALID_MESSAGE");
            return CompletableFuture.completedFuture(false);
        }
        MessageRecord rec = deliveryStore.messageRecord(entry.messageId());
        if (rec == null) return CompletableFuture.completedFuture(false);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (PeerAddress pa : mailboxPeers) {
            futures.add(peerNetwork.storeMessage(pa, env)
                    .thenCompose(receipt -> verifiedReceipt(pa, receipt))
                    .handle((ok, err) -> ok != null && ok)
                    .toCompletableFuture());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenCompose(v -> {
            int verified = 0;
            for (CompletableFuture<Boolean> f : futures) {
                try { if (Boolean.TRUE.equals(f.getNow(false))) verified++; } catch (Exception ignored) {}
            }
            if (verified < storagePolicy.requiredReplicas() && !storagePolicy.bestEffortDelivery()) {
                deliveryStore.updateOutgoing(entry.operationId(), entry.state(), entry.retryCount() + 1,
                        System.currentTimeMillis() + 60_000, "STORAGE_QUORUM_NOT_MET");
                return CompletableFuture.completedFuture(false);
            }
            return identityService.resolve(rec.recipientIdentity()).thenCompose(identity -> {
                byte[] inboxTag = NotificationTag.generate(identity.encryptionPublicKey(), entry.messageId());
                NotificationRequest nr = new NotificationRequest(networkId, contractAddress, entry.messageId(),
                        env.contentHash(), inboxTag, env.expiresAt(), senderWallet);
                return polygon.publishNotification(nr).thenApply(tx -> {
                    deliveryStore.updateOutgoing(entry.operationId(), "NOTIFICATION_SUBMITTED",
                            entry.retryCount(), System.currentTimeMillis(), "");
                    deliveryStore.updateMessageNotification(entry.messageId(), tx);
                    updateMessage(entry.messageId(), "NOTIFICATION_SUBMITTED", "UNVERIFIED", "");
                    return true;
                });
            });
        }).exceptionally(err -> false);
    }

    private void persistOutgoing(String operationId, String messageId, String state, int retryCount, long nextAttemptAt, String lastError, byte[] payload) {
        if (deliveryStore != null) {
            deliveryStore.saveOutgoing(new OutgoingEntry(operationId, messageId, "SEND", state, retryCount, nextAttemptAt, lastError, payload));
        }
    }

    private void updateOutgoing(String operationId, String state, int retryCount, long nextAttemptAt, String lastError) {
        if (deliveryStore != null) {
            deliveryStore.updateOutgoing(operationId, state, retryCount, nextAttemptAt, lastError);
        }
    }

    private void failOutgoing(String operationId, String messageId, String errorCode) {
        updateOutgoing(operationId, "FAILED", 0, System.currentTimeMillis(), errorCode);
        updateMessage(messageId, "FAILED", "UNVERIFIED", errorCode);
    }

    private void persistMessage(MessageRecord record) {
        if (deliveryStore != null) deliveryStore.saveMessageRecord(record);
    }

    private void updateMessage(String messageId, String deliveryState, String verificationState, String lastError) {
        if (deliveryStore != null) deliveryStore.updateMessageState(messageId, deliveryState, verificationState, lastError);
    }

    private static String errorCodeOf(Throwable err) {
        Throwable t = err;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof PolyMailException p) return p.code().name();
        return "UNKNOWN";
    }

    @Override
    public CompletionStage<Void> processNotification(NotificationEvent event, String senderAddress, byte[] recipientEncryptionPrivateKey) {
        if (hasDelivered(event.messageId())) {
            return CompletableFuture.completedFuture(null); // idempotent (spec section 7.4)
        }
        if (event.expiresAt() > 0 && System.currentTimeMillis() > event.expiresAt()) {
            return CompletableFuture.failedFuture(new PolyMailException(com.graalmail.core.error.ErrorCode.MESSAGE_EXPIRED, "notification expired"));
        }
        // Resolve and authenticate the sender first: a valid blockchain event alone never
        // proves authorship (spec section 8.4).
        return identityService.resolve(senderAddress).thenCompose(senderIdentity -> {
            if (!identityService.verify(senderIdentity)) {
                return CompletableFuture.<Void>failedFuture(new PolyMailException(
                        com.graalmail.core.error.ErrorCode.KEY_UNTRUSTED, "sender identity untrusted"));
            }
            return fetchFromAlternates(event).thenCompose(env -> {
                verifyEnvelope(event, env, senderIdentity.signingPublicKey());
                byte[] plaintext;
                try {
                    plaintext = crypto.decrypt(env.ciphertext(), recipientEncryptionPrivateKey, env.encryptionMetadata());
                } catch (PolyMailException e) {
                    return CompletableFuture.<Void>failedFuture(e);
                }
                inbox.save(event.messageId(), env, plaintext);
                if (deliveryStore != null) {
                    deliveryStore.saveMessageRecord(new MessageRecord(event.messageId(), "IN",
                            senderAddress, "", System.currentTimeMillis(), env.expiresAt(),
                            hex(env.contentHash()), event.transactionHash(), "DELIVERED", "VERIFIED", ""));
                }
                return CompletableFuture.<Void>completedFuture(null);
            });
        });
    }

    /** Verifies a storage receipt against the storing peer's key; false if unknown or invalid. */
    private CompletionStage<Boolean> verifiedReceipt(PeerAddress peer, StorageReceipt receipt) {
        if (receipt == null || receipt.signature() == null) return CompletableFuture.completedFuture(false);
        return peerNetwork.peerSigningKey(peer).thenApply(key -> {
            if (key.isEmpty()) return false;
            if (!ProtocolConstants.PROTOCOL_VERSION.equals(receipt.protocolVersion())) return false;
            return crypto.verify(key.get(), receipt.signingBytes(), receipt.signature());
        }).exceptionally(err -> false);
    }

    /** Tries each known peer in turn without blocking; fails with CONTENT_NOT_FOUND if none serve the content. */
    private CompletionStage<Envelope> fetchFromAlternates(NotificationEvent event) {
        return peerNetwork.discoverPeers().thenCompose(peers -> tryPeer(peers, 0, hex(event.contentHash())));
    }

    private CompletionStage<Envelope> tryPeer(List<PeerAddress> peers, int index, String contentId) {
        if (index >= peers.size()) {
            return CompletableFuture.failedFuture(new PolyMailException(
                    com.graalmail.core.error.ErrorCode.CONTENT_NOT_FOUND, "content not found: " + contentId));
        }
        return peerNetwork.fetchMessage(peers.get(index), contentId)
                .handle((env, err) -> (Envelope) env)
                .thenCompose(env -> env != null
                        ? CompletableFuture.completedFuture(env)
                        : tryPeer(peers, index + 1, contentId));
    }

    /**
     * Verifies envelope integrity and sender authenticity before anything is displayed
     * as authenticated mail (spec sections 8.4, 9.3, 11.3).
     */
    private void verifyEnvelope(NotificationEvent event, Envelope env, byte[] senderSigningPublicKey) {
        if (!ProtocolConstants.PROTOCOL_VERSION.equals(env.protocolVersion())) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "unsupported protocol: " + env.protocolVersion());
        }
        if (!networkId.code().equals(env.networkId())) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "network id mismatch");
        }
        if (!event.messageId().equals(env.messageId())) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "message id mismatch");
        }
        if (env.isExpired(System.currentTimeMillis())) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.MESSAGE_EXPIRED, "message expired");
        }
        if (env.ciphertext() == null || env.ciphertext().length > ProtocolConstants.MAX_MESSAGE_BYTES) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "invalid content size");
        }
        byte[] recomputed = crypto.contentHash(env.unsignedContentBytes());
        if (!Arrays.equals(recomputed, event.contentHash()) || !Arrays.equals(recomputed, env.contentHash())) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.CONTENT_HASH_MISMATCH, "hash mismatch");
        }
        if (env.signature() == null || !crypto.verify(senderSigningPublicKey, env.signingBytes(), env.signature())) {
            throw new PolyMailException(com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "sender signature invalid");
        }
    }

    @Override
    public List<String> inboxMessageIds() { return inbox.messageIds(); }

    @Override
    public boolean hasDelivered(String messageId) {
        if (inbox.exists(messageId)) return true;
        return deliveryStore != null && deliveryStore.hasMessageRecord(messageId);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
