/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.store;

/** Encrypts and decrypts secret material at rest. */
public interface SecretProtector {

    byte[] encrypt(byte[] plaintext);

    byte[] decrypt(byte[] ciphertext);

    String algorithm();
}
