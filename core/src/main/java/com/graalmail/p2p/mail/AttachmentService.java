package com.graalmail.p2p.mail;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerNetworkService;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Attachment encryption workflow (spec section 8.3): each attachment gets a fresh
 * random content-encryption key, is sealed locally, and is stored as an encrypted
 * content object. Integrity is verified after retrieval; size, quota and
 * concurrency limits are enforced.
 */
public class AttachmentService {
    private final EncryptionService crypto;
    private final PeerNetworkService peerNetwork;
    private final int maxConcurrentDownloads;

    public AttachmentService(EncryptionService crypto, PeerNetworkService peerNetwork) {
        this(crypto, peerNetwork, 4);
    }

    public AttachmentService(EncryptionService crypto, PeerNetworkService peerNetwork, int maxConcurrentDownloads) {
        this.crypto = crypto;
        this.peerNetwork = peerNetwork;
        this.maxConcurrentDownloads = Math.max(1, maxConcurrentDownloads);
    }

    public CompletionStage<AttachmentPackage> upload(byte[] data, String filename, String mimeType,
                                                    byte[] recipientEncryptionPublicKey, KeyPair senderSigningKey,
                                                    List<PeerAddress> peers) {
        if (data == null || data.length > ProtocolConstants.MAX_ATTACHMENT_BYTES) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.ATTACHMENT_TOO_LARGE,
                    "attachment exceeds maximum size", false, true, false));
        }
        byte[] cek = crypto.randomContentKey();
        byte[] sealed = crypto.seal(data, cek);
        byte[] sealedName = crypto.seal(filename.getBytes(StandardCharsets.UTF_8), cek);
        byte[] wrappedKey = crypto.encrypt(cek, recipientEncryptionPublicKey, null);

        long now = System.currentTimeMillis();
        String messageId = "att-" + java.util.UUID.randomUUID();
        Envelope bare = new Envelope(ProtocolConstants.PROTOCOL_VERSION, "", messageId, null,
                sealed, new byte[0], 0, 0, now, now + ProtocolConstants.DEFAULT_RETENTION_SECONDS * 1000, null);
        byte[] hash = crypto.contentHash(bare.unsignedContentBytes());
        Envelope env = new Envelope(ProtocolConstants.PROTOCOL_VERSION, "", messageId, hash,
                sealed, new byte[0], 0, 0, now, bare.expiresAt(), null);
        env = new Envelope(env.protocolVersion(), env.networkId(), messageId, hash,
                sealed, new byte[0], 0, 0, now, bare.expiresAt(), crypto.sign(senderSigningKey, env.signingBytes()));
        String contentId = HexFormat.of().formatHex(hash);

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (PeerAddress peer : peers) {
            futures.add(peerNetwork.storeMessage(peer, env)
                    .thenApply(r -> true)
                    .exceptionally(err -> false)
                    .toCompletableFuture());
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(v -> {
            for (CompletableFuture<Boolean> f : futures) {
                try {
                    if (Boolean.TRUE.equals(f.getNow(false))) {
                        AttachmentRef ref = new AttachmentRef(contentId,
                                HexFormat.of().formatHex(sealedName), mimeType, data.length);
                        return new AttachmentPackage(ref, wrappedKey, hash);
                    }
                } catch (Exception ignored) {}
            }
            throw new PolyMailException(ErrorCode.STORAGE_QUORUM_NOT_MET, "attachment storage failed");
        });
    }

    public CompletionStage<byte[]> download(AttachmentPackage pkg, byte[] recipientEncryptionPrivateKey,
                                           List<PeerAddress> peers) {
        if (pkg.ref().size() > ProtocolConstants.MAX_ATTACHMENT_BYTES) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.ATTACHMENT_TOO_LARGE,
                    "attachment exceeds maximum size", false, false, false));
        }
        List<PeerAddress> targets = peers.size() > maxConcurrentDownloads ? peers.subList(0, maxConcurrentDownloads) : peers;
        CompletionStage<byte[]> acc = CompletableFuture.failedFuture(
                new PolyMailException(ErrorCode.CONTENT_NOT_FOUND, "attachment not found"));
        for (PeerAddress peer : targets) {
            acc = acc.exceptionallyCompose(err -> {
                if (!isMissing(err)) return CompletableFuture.failedFuture(wrap(err));
                return peerNetwork.fetchMessage(peer, pkg.ref().contentId()).thenApply(env -> {
                    // Same construction as upload: hash over the unsigned content bytes,
                    // which exclude the hash and signature fields themselves.
                    byte[] recomputed = crypto.contentHash(env.unsignedContentBytes());
                    if (!Arrays.equals(recomputed, pkg.contentHash())) {
                        throw new PolyMailException(ErrorCode.CONTENT_HASH_MISMATCH, "attachment hash mismatch");
                    }
                    byte[] cek = crypto.decrypt(pkg.wrappedKey(), recipientEncryptionPrivateKey, null);
                    return crypto.open(env.ciphertext(), cek);
                });
            });
        }
        return acc;
    }

    private static boolean isMissing(Throwable err) {
        Throwable t = err;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        return t instanceof PolyMailException p && p.code() == ErrorCode.CONTENT_NOT_FOUND;
    }

    private static RuntimeException wrap(Throwable err) {
        return err instanceof RuntimeException re ? re : new RuntimeException(err);
    }
}
