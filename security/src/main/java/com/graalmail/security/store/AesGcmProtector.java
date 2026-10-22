/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.store;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM protector whose key is derived from a passphrase with PBKDF2. */
public final class AesGcmProtector implements SecretProtector {

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmProtector(char[] passphrase, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        this.key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt secret", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(ciphertext, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt secret", e);
        }
    }

    @Override
    public String algorithm() {
        return "AES/GCM/NoPadding";
    }
}
