package com.graalmail.p2p;

import com.graalmail.p2p.identity.PolyMailAddress;

public record SendRequest(
        String senderAddress,
        String recipientAddress,
        byte[] contentBytes,
        java.security.KeyPair senderSigningKey,
        java.security.KeyPair senderEncryptionKey,
        int senderKeyVersion,
        long expiresAt
) {}