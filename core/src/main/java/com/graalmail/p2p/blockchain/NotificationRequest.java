package com.graalmail.p2p.blockchain;

import com.graalmail.p2p.NetworkId;

public record NotificationRequest(
        NetworkId networkId,
        String contractAddress,
        String messageId,
        byte[] contentHash,
        byte[] inboxTag,
        long expiresAt,
        String submitterAddress
) {}