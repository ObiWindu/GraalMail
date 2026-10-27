package com.graalmail.p2p.mailbox;

public record StorageReceipt(
        String contentId,
        String messageId,
        String peerId,
        long acceptedAt,
        long retentionUntil,
        String protocolVersion,
        byte[] signature
) {}