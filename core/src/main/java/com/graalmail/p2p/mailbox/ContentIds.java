package com.graalmail.p2p.mailbox;

/** Content addressing helpers (spec section 7.5). */
public final class ContentIds {
    private ContentIds() {}

    /** Hex rendering of a content hash; the canonical content identifier string. */
    public static String hex(byte[] contentHash) {
        StringBuilder sb = new StringBuilder(contentHash.length * 2);
        for (byte b : contentHash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
