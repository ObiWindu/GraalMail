package com.graalmail.p2p;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * One-time recipient notification tags (spec sections 5.2 and 17.2).
 *
 * <p>The tag is {@code SHA-256("PolyMail-InboxTag-v1" || recipientEncryptionPublicKey || messageId)}.
 * Because every message uses a fresh random message ID, tags are unlinkable across messages and
 * no permanent recipient address appears in the on-chain event. The recipient recognises relevant
 * events by recomputing the tag from its own public encryption key and the event's public
 * {@code messageId} topic (see {@link #matches}).
 *
 * <p>This is not an anonymity mechanism: an observer holding the public identity registry can
 * trial-compute tags per event. See the privacy model in the specification.
 */
public final class NotificationTag {
    private static final byte[] DOMAIN = "PolyMail-InboxTag-v1".getBytes(StandardCharsets.UTF_8);

    private NotificationTag() {}

    public static byte[] generate(byte[] recipientEncryptionPublicKey, String messageId) {
        if (recipientEncryptionPublicKey == null || recipientEncryptionPublicKey.length == 0) {
            throw new IllegalArgumentException("recipient public key required");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId required");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(DOMAIN);
            md.update(recipientEncryptionPublicKey);
            md.update(messageId.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Constant-time check whether {@code tag} is the recipient's tag for {@code messageId}. */
    public static boolean matches(byte[] recipientEncryptionPublicKey, String messageId, byte[] tag) {
        if (tag == null || tag.length != 32) return false;
        byte[] expected;
        try {
            expected = generate(recipientEncryptionPublicKey, messageId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, tag);
    }

    /**
     * Legacy address-bound tag. Kept for compatibility only; new code must use
     * {@link #generate(byte[], String)}. The address string is low-entropy and predictable,
     * so this construction offers weaker unlinkability.
     */
    @Deprecated
    public static byte[] generate(String messageId, String recipientAddress) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(messageId.getBytes(StandardCharsets.UTF_8));
            md.update(recipientAddress.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
