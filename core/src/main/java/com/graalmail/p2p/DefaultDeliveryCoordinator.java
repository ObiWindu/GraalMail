package com.graalmail.p2p;

import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.blockchain.NotificationRequest;
import com.graalmail.p2p.blockchain.PolygonNotificationService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.identity.IdentityService;
import com.graalmail.p2p.mailbox.InboxRepository;
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
    private final int requiredReplicas;

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
        this.identityService = identityService;
        this.crypto = crypto;
        this.peerNetwork = peerNetwork;
        this.polygon = polygon;
        this.inbox = inbox;
        this.mailboxPeers = new ArrayList<>(mailboxPeers);
        this.networkId = networkId;
        this.contractAddress = contractAddress;
        this.senderWallet = senderWallet;
        this.requiredReplicas = Math.max(1, requiredReplicas);
    }

    @Override
    public CompletionStage<SendResult> send(SendRequest request) {
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

            // Phase B: store content on the required number of peers (spec section 10.2).
            List<CompletableFuture<StorageReceipt>> futures = new ArrayList<>();
            for (PeerAddress pa : mailboxPeers) {
                futures.add(peerNetwork.storeMessage(pa, finalEnv).toCompletableFuture());
            }
            CompletionStage<Void> stored = CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                    .thenApply(v -> {
                        int success = 0;
                        for (CompletableFuture<StorageReceipt> f : futures) {
                            try {
                                if (f.getNow(null) != null) success++;
                            } catch (Exception ignored) {}
                        }
                        if (success < requiredReplicas) {
                            throw new PolyMailException(com.graalmail.core.error.ErrorCode.STORAGE_QUORUM_NOT_MET, "storage quorum not met");
                        }
                        return null;
                    });
            // Phase C: publish the notification only after storage is confirmed (spec section 10.3).
            // Storage and notification errors stay distinct so callers can apply the right
            // recovery policy (spec sections 14.2-14.3).
            return stored.thenCompose(v -> {
                byte[] inboxTag = NotificationTag.generate(identity.encryptionPublicKey(), messageId);
                NotificationRequest nr = new NotificationRequest(networkId, contractAddress, messageId, hash, inboxTag, expiresAt, senderWallet);
                return polygon.publishNotification(nr)
                        .thenApply(tx -> new SendResult(messageId, tx, DeliveryState.NOTIFICATION_SUBMITTED));
            });
        });
    }

    @Override
    public CompletionStage<Void> processNotification(NotificationEvent event, String senderAddress, byte[] recipientEncryptionPrivateKey) {
        if (inbox.exists(event.messageId())) {
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
                return CompletableFuture.<Void>completedFuture(null);
            });
        });
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

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
