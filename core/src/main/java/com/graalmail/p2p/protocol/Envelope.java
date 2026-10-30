package com.graalmail.p2p.protocol;

import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;
import com.graalmail.core.error.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public record Envelope(
        String protocolVersion,
        String networkId,
        String messageId,
        byte[] contentHash,
        byte[] ciphertext,
        byte[] encryptionMetadata,
        int senderKeyVersion,
        int recipientKeyVersion,
        long createdAt,
        long expiresAt,
        byte[] signature
) {
    public byte[] unsignedContentBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeString(out, protocolVersion);
            writeString(out, networkId);
            writeString(out, messageId);
            writeBytes(out, ciphertext);
            writeBytes(out, encryptionMetadata == null ? new byte[0] : encryptionMetadata);
            out.writeInt(senderKeyVersion);
            out.writeInt(recipientKeyVersion);
            out.writeLong(createdAt);
            out.writeLong(expiresAt);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "serialization failed", e);
        }
    }

    public byte[] signingBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeString(out, protocolVersion);
            writeString(out, networkId);
            writeString(out, messageId);
            writeBytes(out, contentHash);
            out.writeInt(senderKeyVersion);
            out.writeInt(recipientKeyVersion);
            out.writeLong(createdAt);
            out.writeLong(expiresAt);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "serialization failed", e);
        }
    }

    public byte[] toBytes() {
        return toCbor();
    }

    public byte[] toCbor() {
        try {
            com.upokecenter.cbor.CBORObject obj = com.upokecenter.cbor.CBORObject.NewMap();
            obj.set("protocolVersion", com.upokecenter.cbor.CBORObject.FromObject(protocolVersion));
            obj.set("networkId", com.upokecenter.cbor.CBORObject.FromObject(networkId));
            obj.set("messageId", com.upokecenter.cbor.CBORObject.FromObject(messageId));
            obj.set("contentHash", com.upokecenter.cbor.CBORObject.FromObject(contentHash == null ? new byte[0] : contentHash));
            obj.set("ciphertext", com.upokecenter.cbor.CBORObject.FromObject(ciphertext == null ? new byte[0] : ciphertext));
            obj.set("encryptionMetadata", com.upokecenter.cbor.CBORObject.FromObject(encryptionMetadata == null ? new byte[0] : encryptionMetadata));
            obj.set("senderKeyVersion", com.upokecenter.cbor.CBORObject.FromObject(senderKeyVersion));
            obj.set("recipientKeyVersion", com.upokecenter.cbor.CBORObject.FromObject(recipientKeyVersion));
            obj.set("createdAt", com.upokecenter.cbor.CBORObject.FromObject(createdAt));
            obj.set("expiresAt", com.upokecenter.cbor.CBORObject.FromObject(expiresAt));
            obj.set("signature", com.upokecenter.cbor.CBORObject.FromObject(signature == null ? new byte[0] : signature));
            return obj.EncodeToBytes(com.upokecenter.cbor.CBOREncodeOptions.DefaultCtap2Canonical);
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "cbor serialization failed", e);
        }
    }

    public static Envelope fromBytes(byte[] bytes) {
        return fromCbor(bytes);
    }

    public static Envelope fromCbor(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > com.graalmail.p2p.ProtocolConstants.MAX_MESSAGE_BYTES + 1024) {
            throw new com.graalmail.p2p.PolyMailException(com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "envelope size invalid");
        }
        try {
            com.upokecenter.cbor.CBORObject obj = com.upokecenter.cbor.CBORObject.DecodeFromBytes(bytes, com.upokecenter.cbor.CBOREncodeOptions.DefaultCtap2Canonical);
            if (obj == null) {
                throw new com.graalmail.p2p.PolyMailException(com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "envelope not a map");
            }
            return new Envelope(
                    obj.get("protocolVersion") == null ? null : obj.get("protocolVersion").AsString(),
                    obj.get("networkId") == null ? null : obj.get("networkId").AsString(),
                    obj.get("messageId") == null ? null : obj.get("messageId").AsString(),
                    obj.get("contentHash") == null ? null : obj.get("contentHash").GetByteString(),
                    obj.get("ciphertext") == null ? null : obj.get("ciphertext").GetByteString(),
                    obj.get("encryptionMetadata") == null ? null : obj.get("encryptionMetadata").GetByteString(),
                    obj.get("senderKeyVersion") == null ? 0 : obj.get("senderKeyVersion").AsInt32Value(),
                    obj.get("recipientKeyVersion") == null ? 0 : obj.get("recipientKeyVersion").AsInt32Value(),
                    obj.get("createdAt") == null ? 0 : obj.get("createdAt").AsInt64Value(),
                    obj.get("expiresAt") == null ? 0 : obj.get("expiresAt").AsInt64Value(),
                    obj.get("signature") == null ? null : obj.get("signature").GetByteString());
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "cbor deserialization failed", e);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws Exception {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] b) throws Exception {
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBytes(DataInputStream in) throws Exception {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    public boolean isExpired(long now) { return expiresAt > 0 && now > expiresAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Envelope e)) return false;
        return senderKeyVersion == e.senderKeyVersion && recipientKeyVersion == e.recipientKeyVersion
                && createdAt == e.createdAt && expiresAt == e.expiresAt
                && java.util.Objects.equals(protocolVersion, e.protocolVersion)
                && java.util.Objects.equals(networkId, e.networkId)
                && java.util.Objects.equals(messageId, e.messageId)
                && Arrays.equals(contentHash, e.contentHash)
                && Arrays.equals(ciphertext, e.ciphertext)
                && Arrays.equals(encryptionMetadata, e.encryptionMetadata)
                && Arrays.equals(signature, e.signature);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(protocolVersion, networkId, messageId, senderKeyVersion, recipientKeyVersion, createdAt, expiresAt);
    }
}
