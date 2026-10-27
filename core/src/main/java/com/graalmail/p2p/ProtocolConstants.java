package com.graalmail.p2p;

/** Global constants for the PolyMail P2P Mail Protocol. */
public final class ProtocolConstants {
    private ProtocolConstants() {
    }

    public static final String PROTOCOL_VERSION = "GPMP/1";
    public static final String DEFAULT_DOMAIN = "graalemail";
    public static final int MAX_MESSAGE_BYTES = 25 * 1024 * 1024;
    public static final int MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;
    public static final int AES_KEY_BYTES = 32;
    public static final int GCM_IV_BYTES = 12;
    public static final int GCM_TAG_BITS = 128;
    public static final int X25519_KEY_BYTES = 32;
    public static final int CONTENT_HASH_BYTES = 32;
    public static final long DEFAULT_RETENTION_SECONDS = 30L * 24 * 60 * 60;
}
