package com.graalmail.p2p.delivery;

/** Durable send/receive message record (spec section 13.1). */
public record MessageRecord(
        String messageId,
        String direction,
        String senderIdentity,
        String recipientIdentity,
        long createdAt,
        long expiresAt,
        String contentHashHex,
        String notificationTxHash,
        String deliveryState,
        String verificationState,
        String lastError
) {}
