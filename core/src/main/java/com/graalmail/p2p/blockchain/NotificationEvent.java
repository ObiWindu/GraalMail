package com.graalmail.p2p.blockchain;

public record NotificationEvent(
        String chainId,
        String contractAddress,
        String transactionHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        String messageId,
        byte[] contentHash,
        byte[] inboxTag,
        long expiresAt,
        long observedAt
) {}