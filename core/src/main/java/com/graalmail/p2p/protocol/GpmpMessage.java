package com.graalmail.p2p.protocol;

import com.graalmail.p2p.GpmpOperation;

import java.io.*;
import java.nio.charset.StandardCharsets;

public record GpmpMessage(
        String protocolVersion,
        String networkId,
        GpmpOperation operation,
        String requestId,
        byte[] payload,
        long timestamp
) {
    public byte[] toBytes() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeString(out, protocolVersion);
            writeString(out, networkId);
            writeString(out, operation.name());
            writeString(out, requestId);
            writeBytes(out, payload == null ? new byte[0] : payload);
            out.writeLong(timestamp);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static GpmpMessage fromBytes(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String pv = readString(in);
            String nid = readString(in);
            GpmpOperation op = GpmpOperation.valueOf(readString(in));
            String rid = readString(in);
            byte[] payload = readBytes(in);
            long ts = in.readLong();
            return new GpmpMessage(pv, nid, op, rid, payload, ts);
        } catch (com.graalmail.p2p.PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new com.graalmail.p2p.PolyMailException(
                    com.graalmail.core.error.ErrorCode.INVALID_MESSAGE, "malformed protocol message", e);
        }
    }
    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length); out.write(b);
    }
    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FIELD_BYTES) throw new IOException("string field too large: " + len);
        byte[] b = new byte[len]; in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
    private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        out.writeInt(b.length); out.write(b);
    }
    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > MAX_FIELD_BYTES) throw new IOException("bytes field too large: " + len);
        byte[] b = new byte[len]; in.readFully(b);
        return b;
    }

    /** Hard cap per field so malformed input cannot force huge allocations (spec 18). */
    public static final int MAX_FIELD_BYTES = 32 * 1024 * 1024;
}