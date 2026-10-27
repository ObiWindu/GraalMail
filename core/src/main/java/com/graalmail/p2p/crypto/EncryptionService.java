package com.graalmail.p2p.crypto;

import java.security.KeyPair;
import java.util.concurrent.CompletionStage;

public interface EncryptionService {
    KeyPair createEncryptionKeyPair();
    KeyPair createSigningKeyPair();
    byte[] sign(KeyPair signingKeyPair, byte[] data);
    boolean verify(byte[] signingPublicKeyEncoded, byte[] data, byte[] signature);
    byte[] encrypt(byte[] plaintext, byte[] recipientEncryptionPublicKeyEncoded, byte[] associatedData);
    byte[] decrypt(byte[] ciphertextEnvelope, byte[] recipientEncryptionPrivateKeyEncoded, byte[] associatedData);
    byte[] contentHash(byte[] canonicalBytes);
    KeyMaterial createKeyMaterial();
    /** Symmetric AES-256-GCM encryption with a caller-provided 32-byte key; output is IV || ciphertext. */
    byte[] seal(byte[] plaintext, byte[] key);
    /** Inverse of {@link #seal}. */
    byte[] open(byte[] sealed, byte[] key);
    /** Fresh random content-encryption key. */
    byte[] randomContentKey();
}