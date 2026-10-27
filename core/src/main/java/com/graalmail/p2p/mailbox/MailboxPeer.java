package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;

import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class MailboxPeer {
    private final String peerId;
    private final ContentStore store;
    private final KeyPair signingKey;
    private final EncryptionService crypto;

    public MailboxPeer(String peerId, ContentStore store, KeyPair signingKey) {
        this.peerId = peerId;
        this.store = store;
        this.signingKey = signingKey;
        this.crypto = new DefaultEncryptionService();
    }

    public CompletionStage<StorageReceipt> store(Envelope envelope) {
        String contentId = hex(envelope.contentHash());
        if (store.contains(contentId)) {
            // idempotent: already stored
        }
        store.store(contentId, envelope.toBytes());
        byte[] receiptPayload = (contentId + "|" + envelope.messageId() + "|" + peerId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] sig = crypto.sign(signingKey, receiptPayload);
        long now = System.currentTimeMillis();
        StorageReceipt receipt = new StorageReceipt(contentId, envelope.messageId(), peerId, now, now + ProtocolConstants.DEFAULT_RETENTION_SECONDS * 1000, ProtocolConstants.PROTOCOL_VERSION, sig);
        return CompletableFuture.completedFuture(receipt);
    }

    public CompletionStage<Envelope> fetch(String contentId) {
        byte[] bytes = store.fetch(contentId);
        if (bytes == null) return CompletableFuture.failedFuture(new PolyMailException(com.graalmail.core.error.ErrorCode.CONTENT_NOT_FOUND, "not found: " + contentId));
        return CompletableFuture.completedFuture(Envelope.fromBytes(bytes));
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
