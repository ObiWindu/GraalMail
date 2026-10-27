/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Ensures the BouncyCastle provider is registered exactly once. */
public final class ProtonCrypto {

    private ProtonCrypto() {
    }

    public static void ensureRegistered() {
        // Check the actual registry every time, not a cached flag: the
        // provider can be missing even after a previous registration.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            synchronized (ProtonCrypto.class) {
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(new BouncyCastleProvider());
                }
            }
        }
    }

    /** An unlocked key pair usable for decryption. */
    public record UnlockedKey(org.bouncycastle.openpgp.PGPPublicKey publicKey,
                              org.bouncycastle.openpgp.PGPPrivateKey privateKey) {
    }

    static List<UnlockedKey> emptyKeys() {
        return new ArrayList<>();
    }
}
