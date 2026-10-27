package com.graalmail.p2p;

public record SendRequest(
        String senderAddress,
        String recipientAddress,
        byte[] contentBytes,
        java.security.KeyPair senderSigningKey,
        java.security.KeyPair senderEncryptionKey,
        int senderKeyVersion,
        long expiresAt,
        /** Spam-deposit funding transaction hash; blank unless the deposit policy requires it. */
        String depositTxProof
) {
    public SendRequest(String senderAddress, String recipientAddress, byte[] contentBytes,
                       java.security.KeyPair senderSigningKey, java.security.KeyPair senderEncryptionKey,
                       int senderKeyVersion, long expiresAt) {
        this(senderAddress, recipientAddress, contentBytes, senderSigningKey, senderEncryptionKey,
                senderKeyVersion, expiresAt, "");
    }
}
