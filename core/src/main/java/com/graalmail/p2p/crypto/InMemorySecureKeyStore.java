package com.graalmail.p2p.crypto;

import com.graalmail.p2p.PolyMailException;

import java.security.KeyPair;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemorySecureKeyStore implements SecureKeyStore {
    private final EncryptionService crypto = new DefaultEncryptionService();
    private final Map<KeyReference, KeyPair> keys = new ConcurrentHashMap<>();

    @Override
    public KeyReference createEncryptionKey() {
        KeyReference ref = KeyReference.encryption();
        keys.put(ref, crypto.createEncryptionKeyPair());
        return ref;
    }

    @Override
    public KeyReference createSigningKey() {
        KeyReference ref = KeyReference.signing();
        keys.put(ref, crypto.createSigningKeyPair());
        return ref;
    }

    @Override
    public byte[] sign(KeyReference key, byte[] data) {
        KeyPair kp = keys.get(key);
        if (kp == null) throw new PolyMailException(com.graalmail.core.error.ErrorCode.KEY_UNTRUSTED, "unknown key");
        return crypto.sign(kp, data);
    }

    @Override
    public byte[] decrypt(KeyReference key, byte[] ciphertext) {
        KeyPair kp = keys.get(key);
        if (kp == null) throw new PolyMailException(com.graalmail.core.error.ErrorCode.KEY_UNTRUSTED, "unknown key");
        return crypto.decrypt(ciphertext, kp.getPrivate().getEncoded(), new byte[0]);
    }
}
