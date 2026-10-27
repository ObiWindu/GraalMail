package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;
import com.graalmail.core.error.ErrorCode;

import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class MailboxPeer {
    private final String peerId;
    private final ContentStore store;
    private final KeyPair signingKey;
    private final EncryptionService crypto;
    private final QuotaTracker quotas;

    public MailboxPeer(String peerId, ContentStore store, KeyPair signingKey) {
        this(peerId, store, signingKey, QuotaTracker.unlimited());
    }

    public MailboxPeer(String peerId, ContentStore store, KeyPair signingKey, QuotaTracker quotas) {
        this.peerId = peerId;
        this.store = store;
        this.signingKey = signingKey;
        this.crypto = new DefaultEncryptionService();
        this.quotas = quotas;
    }

    public CompletionStage<StorageReceipt> store(Envelope envelope) {
        byte[] wire;
        try {
            wire = envelope.toBytes();
        } catch (PolyMailException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (wire.length > ProtocolConstants.MAX_MESSAGE_BYTES) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.INVALID_MESSAGE,
                    "content exceeds maximum size", false, false, true));
        }
        if (envelope.isExpired(System.currentTimeMillis())) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.MESSAGE_EXPIRED,
                    "content already expired", false, false, false));
        }
        String contentId = hex(envelope.contentHash());
        if (!store.contains(contentId)) {
            if (!quotas.tryAcquire(peerId, wire.length)) {
                return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.STORAGE_ERROR,
                        "storage quota exceeded", true, false, true));
            }
            store.store(contentId, wire);
        }
        long now = System.currentTimeMillis();
        long retentionUntil = now + ProtocolConstants.DEFAULT_RETENTION_SECONDS * 1000;
        byte[] sig = crypto.sign(signingKey, StorageReceipt.signingBytes(
                contentId, envelope.messageId(), peerId, now, retentionUntil, ProtocolConstants.PROTOCOL_VERSION));
        StorageReceipt receipt = new StorageReceipt(contentId, envelope.messageId(), peerId,
                now, retentionUntil, ProtocolConstants.PROTOCOL_VERSION, sig);
        return CompletableFuture.completedFuture(receipt);
    }

    public CompletionStage<Envelope> fetch(String contentId) {
        byte[] bytes = store.fetch(contentId);
        if (bytes == null) return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.CONTENT_NOT_FOUND, "not found: " + contentId));
        Envelope env;
        try {
            env = Envelope.fromBytes(bytes);
        } catch (PolyMailException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (env.isExpired(System.currentTimeMillis())) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.MESSAGE_EXPIRED,
                    "content expired", false, false, false));
        }
        return CompletableFuture.completedFuture(env);
    }

    public String peerId() { return peerId; }
    public ContentStore store() { return store; }
    public KeyPair signingKey() { return signingKey; }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
