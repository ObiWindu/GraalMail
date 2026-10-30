package com.graalmail.p2p.mailbox;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PolyMailException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

    /**
     * Deterministic wire encoding for {@code STORE_MESSAGE} replies (spec 7):
     * length-prefixed UTF-8 strings, two int64 timestamps, length-prefixed
     * signature. Field caps mirror the fuzz-hardening policy — oversized or
     * trailing bytes are rejected, never trusted.
     */
    public byte[] toBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeString(out, contentId, MAX_CONTENT_ID_BYTES);
            writeString(out, messageId, MAX_MESSAGE_ID_BYTES);
            writeString(out, peerId, MAX_PEER_ID_BYTES);
            out.writeLong(acceptedAt);
            out.writeLong(retentionUntil);
            writeString(out, protocolVersion, MAX_VERSION_BYTES);
            byte[] sig = signature == null ? new byte[0] : signature;
            if (sig.length > MAX_SIGNATURE_BYTES) {
                throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt signature too large");
            }
            out.writeInt(sig.length);
            out.write(sig);
            out.flush();
            return baos.toByteArray();
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt serialization failed", e);
        }
    }

    /** Inverse of {@link #toBytes}; rejects truncated input and trailing bytes. */
    public static StorageReceipt fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt bytes missing");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String contentId = readString(in, MAX_CONTENT_ID_BYTES);
            String messageId = readString(in, MAX_MESSAGE_ID_BYTES);
            String peerId = readString(in, MAX_PEER_ID_BYTES);
            long acceptedAt = in.readLong();
            long retentionUntil = in.readLong();
            String protocolVersion = readString(in, MAX_VERSION_BYTES);
            int sigLen = in.readInt();
            if (sigLen < 0 || sigLen > MAX_SIGNATURE_BYTES) {
                throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt signature too large");
            }
            byte[] signature = new byte[sigLen];
            in.readFully(signature);
            if (in.available() != 0) {
                throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt has trailing bytes");
            }
            return new StorageReceipt(contentId, messageId, peerId, acceptedAt,
                    retentionUntil, protocolVersion, signature);
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt deserialization failed", e);
        }
    }

    private static final int MAX_CONTENT_ID_BYTES = 256;
    private static final int MAX_MESSAGE_ID_BYTES = 1024;
    private static final int MAX_PEER_ID_BYTES = 256;
    private static final int MAX_VERSION_BYTES = 64;
    private static final int MAX_SIGNATURE_BYTES = 512;

    private static void writeString(DataOutputStream out, String value, int cap) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > cap) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt field too large");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int cap) throws Exception {
        int length = in.readInt();
        if (length < 0 || length > cap) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "receipt field too large");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
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
        return java.util.Objects.hash(contentId, messageId, peerId, acceptedAt, retentionUntil, protocolVersion,
                java.util.Arrays.hashCode(signature));
    }
}
