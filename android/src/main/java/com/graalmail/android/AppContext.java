/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.graalmail.security.store.AesGcmProtector;
import com.graalmail.security.store.CredentialStore;
import com.graalmail.security.store.FileCredentialStore;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.SqlMailStore;
import com.graalmail.sync.AccountSessionFactory;
import com.graalmail.sync.SyncEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * The application context of the Android client.
 *
 * <p>The context owns the local database, the credential store and the
 * synchronisation engine. The credential store is protected with a key that
 * lives in the Android key store, so the secrets cannot be read from a backup
 * of the application data.</p>
 */
public final class AppContext {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "graalmail.master";
    private static final String PREFS = "graalmail.secrets";
    private static final String PREFS_MASTER = "master";
    private static final String PREFS_SALT = "salt";

    private static volatile AppContext instance;

    private final Context context;
    private final MailStore store;
    private final CredentialStore credentialStore;
    private final AccountSessionFactory sessions;
    private final SyncEngine syncEngine;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private AppContext(Context context) {
        this.context = context.getApplicationContext();
        File database = new File(this.context.getFilesDir(), "graalmail.db");
        JdbcSqlDatabase db = new JdbcSqlDatabase("jdbc:sqlite:" + database.getAbsolutePath());
        SqlMailStore mailStore = new SqlMailStore(db);
        mailStore.initialize();
        this.store = mailStore;
        this.credentialStore = openCredentialStore(this.context);
        this.sessions = new AccountSessionFactory(this.credentialStore);
        this.syncEngine = new SyncEngine(this.store, this.sessions);
    }

    /**
     * Returns the shared application context.
     *
     * @param context any context of the application
     * @return the application context
     */
    public static AppContext of(Context context) {
        AppContext local = instance;
        if (local == null) {
            synchronized (AppContext.class) {
                local = instance;
                if (local == null) {
                    local = new AppContext(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public Context context() {
        return context;
    }

    public MailStore store() {
        return store;
    }

    public CredentialStore credentialStore() {
        return credentialStore;
    }

    public AccountSessionFactory sessions() {
        return sessions;
    }

    public SyncEngine syncEngine() {
        return syncEngine;
    }

    /**
     * The executor for the work that must not run on the main thread.
     *
     * @return the single background thread
     */
    public ExecutorService io() {
        return io;
    }

    private static CredentialStore openCredentialStore(Context context) {
        try {
            char[] passphrase = masterPassphrase(context);
            byte[] salt = salt(context);
            File file = new File(context.getFilesDir(), "credentials.bin");
            return new FileCredentialStore(file.toPath(),
                    new AesGcmProtector(passphrase, salt));
        } catch (Exception e) {
            throw new IllegalStateException("The credential store could not be opened", e);
        }
    }

    private static char[] masterPassphrase(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(PREFS_MASTER, "");
        byte[] secret;
        if (!stored.isEmpty()) {
            secret = decrypt(context, Base64.getDecoder().decode(stored));
        } else {
            secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);
            prefs.edit().putString(PREFS_MASTER,
                    Base64.getEncoder().encodeToString(encrypt(context, secret))).apply();
        }
        return Base64.getEncoder().encodeToString(secret).toCharArray();
    }

    private static byte[] decrypt(Context context, byte[] cipherText) throws Exception {
        SecretKey key = keystoreKey();
        byte[] iv = new byte[12];
        System.arraycopy(cipherText, 0, iv, 0, iv.length);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(cipherText, iv.length, cipherText.length - iv.length);
    }

    private static byte[] salt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(PREFS_SALT, "");
        if (!stored.isEmpty()) {
            return Base64.getDecoder().decode(stored);
        }
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        prefs.edit().putString(PREFS_SALT, Base64.getEncoder().encodeToString(salt)).apply();
        return salt;
    }

    private static byte[] encrypt(Context context, byte[] plain) throws Exception {
        SecretKey key = keystoreKey();
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
        return out;
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry secretEntry) {
            return secretEntry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
