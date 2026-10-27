package com.graalmail.p2p.mailbox;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record StorageReceipt(
        String contentId,
        String messageId,
        String peerId,
        long acceptedAt,
        long retentionUntil,
        String protocolVersion,
        byte[] signature
) {
    /**
     * Canonical bytes covered by the peer's signature (spec section 9.2). Every receipt
     * field is bound so a receipt cannot be transplanted onto different content, a
     * different peer, or a different retention commitment.
     */
    public static byte[] signingBytes(String contentId, String messageId, String peerId,
                                      long acceptedAt, long retentionUntil, String protocolVersion) {
        String s = contentId + "|" + messageId + "|" + peerId + "|" + acceptedAt + "|" + retentionUntil + "|" + protocolVersion;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] signingBytes() {
        return signingBytes(contentId, messageId, peerId, acceptedAt, retentionUntil, protocolVersion);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StorageReceipt r)) return false;
        return acceptedAt == r.acceptedAt && retentionUntil == r.retentionUntil
                && java.util.Objects.equals(contentId, r.contentId)
                && java.util.Objects.equals(messageId, r.messageId)
                && java.util.Objects.equals(peerId, r.peerId)
                && java.util.Objects.equals(protocolVersion, r.protocolVersion)
                && Arrays.equals(signature, r.signature);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(contentId, messageId, peerId, acceptedAt, retentionUntil, protocolVersion);
    }
}
