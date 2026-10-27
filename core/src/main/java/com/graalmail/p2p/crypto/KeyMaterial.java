package com.graalmail.p2p.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

public record KeyMaterial(KeyPair signingKeyPair, KeyPair encryptionKeyPair) {
    public PrivateKey signingPrivate() { return signingKeyPair.getPrivate(); }
    public PublicKey signingPublic() { return signingKeyPair.getPublic(); }
    public PrivateKey encryptionPrivate() { return encryptionKeyPair.getPrivate(); }
    public PublicKey encryptionPublic() { return encryptionKeyPair.getPublic(); }
}