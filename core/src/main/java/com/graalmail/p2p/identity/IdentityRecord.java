package com.graalmail.p2p.identity;

import java.util.List;

public record IdentityRecord(
        String username,
        String walletAddress,
        byte[] signingPublicKey,
        byte[] encryptionPublicKey,
        int keyVersion,
        List<String> authorizedDevices,
        List<String> mailboxEndpoints,
        long createdAt,
        long expiresAt,
        boolean revoked,
        byte[] signature
) {
    public IdentityRecord {
        if (authorizedDevices == null) authorizedDevices = List.of();
        if (mailboxEndpoints == null) mailboxEndpoints = List.of();
    }
}