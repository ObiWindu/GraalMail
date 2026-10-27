package com.graalmail.p2p.crypto;

public interface SecureKeyStore {
    KeyReference createEncryptionKey();
    KeyReference createSigningKey();
    byte[] sign(KeyReference key, byte[] data);
    byte[] decrypt(KeyReference key, byte[] ciphertext);
}