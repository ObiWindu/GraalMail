/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.crypto.KeyReference;
import com.graalmail.p2p.crypto.SecureKeyStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android {@link SecureKeyStore} (spec sections 3 and 16.3).
 *
 * <p>X25519/Ed25519 are not AndroidKeyStore-backed on all devices, so the store
 * follows the documented hybrid pattern: software key pairs are generated with
 * the shared crypto service, sealed with a hardware-backed AES-256-GCM
 * key-encryption key from the AndroidKeyStore where the device supports it, and
 * kept in the app-private files directory. Private key bytes only exist
 * unsealed while an operation runs. {@link #decrypt} assumes empty associated
 * data (key-wrap usage); message decryption with message-bound AAD goes through
 * the shared {@link EncryptionService} with keys loaded via backup/restore.
 */
public final class AndroidSecureKeyStore implements SecureKeyStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEK_ALIAS = "polymail-kek";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final File keyDirectory;
    private final EncryptionService crypto = new DefaultEncryptionService();

    public AndroidSecureKeyStore(Context context) {
        this.keyDirectory = new File(context.getFilesDir(), "polymail-keys");
        if (!keyDirectory.exists() && !keyDirectory.mkdirs()) {
            throw new PolyMailException(ErrorCode.STORAGE_ERROR, "cannot create key directory");
        }
    }

    @Override
    public KeyReference createEncryptionKey() {
        return store("encryption", crypto.createEncryptionKeyPair());
    }

    @Override
    public KeyReference createSigningKey() {
        return store("signing", crypto.createSigningKeyPair());
    }

    @Override
    public byte[] sign(KeyReference key, byte[] data) {
        return crypto.sign(load(key, "Ed25519"), data);
    }

    @Override
    public byte[] decrypt(KeyReference key, byte[] ciphertext) {
        KeyPair kp = load(key, "X25519");
        return crypto.decrypt(ciphertext, kp.getPrivate().getEncoded(), new byte[0]);
    }

    private KeyReference store(String type, KeyPair pair) {
        try {
            KeyReference ref = new KeyReference(UUID.randomUUID().toString(), type);
            byte[] priv = pair.getPrivate().getEncoded();
            byte[] pub = pair.getPublic().getEncoded();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(priv.length); out.write(priv);
            out.writeInt(pub.length); out.write(pub);
            out.flush();
            byte[] sealed = seal(baos.toByteArray());
            Arrays.fill(priv, (byte) 0);
            try (FileOutputStream fos = new FileOutputStream(new File(keyDirectory, ref.id() + ".key"))) {
                fos.write(sealed);
            }
            return ref;
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.STORAGE_ERROR, "cannot store key", e);
        }
    }

    private KeyPair load(KeyReference key, String algorithm) {
        try {
            File file = new File(keyDirectory, key.id() + ".key");
            if (!file.exists()) throw new PolyMailException(ErrorCode.KEY_UNTRUSTED, "unknown key");
            byte[] sealed;
            try (FileInputStream fis = new FileInputStream(file)) {
                sealed = fis.readAllBytes();
            }
            byte[] raw = unseal(sealed);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
            int privLen = in.readInt();
            byte[] priv = new byte[privLen]; in.readFully(priv);
            int pubLen = in.readInt();
            byte[] pub = new byte[pubLen]; in.readFully(pub);
            KeyFactory factory = KeyFactory.getInstance(algorithm);
            return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(pub)),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(priv)));
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "cannot load key", e);
        }
    }

    private byte[] seal(byte[] plaintext) throws Exception {
        SecretKey kek = kek();
        byte[] iv = new byte[IV_BYTES];
        new java.security.SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = cipher.doFinal(plaintext);
        byte[] out = new byte[IV_BYTES + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_BYTES);
        System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
        return out;
    }

    private byte[] unseal(byte[] sealed) throws Exception {
        SecretKey kek = kek();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek,
                new GCMParameterSpec(GCM_TAG_BITS, sealed, 0, IV_BYTES));
        return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
    }

    private SecretKey kek() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(KEK_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEK_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEK_ALIAS, null);
    }
}
