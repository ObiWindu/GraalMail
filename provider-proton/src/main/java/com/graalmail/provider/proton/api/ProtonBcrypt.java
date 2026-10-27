/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

/**
 * bcrypt password hashing for Proton SRP, using a server-provided salt.
 *
 * <p>Proton hashes {@code password} with bcrypt cost 10 and the 16-byte salt
 * from {@code /auth/info} (raw salt with {@code "proton"} appended), then
 * rewrites the {@code $2a$} prefix to {@code $2y$}.</p>
 */
public final class ProtonBcrypt {

    private static final int COST = 10;

    private ProtonBcrypt() {
    }

    /**
     * Hashes the password, returning the Modular Crypt Format string bytes
     * (UTF-8) with the {@code $2y$} prefix Proton expects.
     *
     * @param password UTF-8 password bytes
     * @param salt sixteen salt bytes
     */
    public static byte[] hash(byte[] password, byte[] salt) {
        if (salt == null || salt.length != 16) {
            throw new IllegalStateException("SRP salt must be 16 bytes");
        }
        String hashed = OpenBSDBCrypt.generate(password, salt, COST);
        if (hashed.startsWith("$2a$")) {
            hashed = "$2y$" + hashed.substring(4);
        }
        return hashed.getBytes(StandardCharsets.UTF_8);
    }
}
