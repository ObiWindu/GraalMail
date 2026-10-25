/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import com.graalmail.security.store.CredentialStore;
import com.graalmail.security.store.SecretProtector;
import com.graalmail.storage.EncryptedSqlDatabase;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.SqlMailStore;
import com.graalmail.sync.AccountSessionFactory;
import com.graalmail.sync.SyncEngine;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * The desktop application context: the local database, the credential store
 * and the synchronization engine.
 *
 * <p>The database wraps the underlying SQLite connection with column-level
 * encryption for sensitive message data (bodies, headers, attachments).
 */
public final class DesktopContext implements AutoCloseable {

    private static final Logger LOG = Log.of(DesktopContext.class);

    private final AppPaths paths;
    private final MailStore store;
    private final CredentialStore credentialStore;
    private final AccountSessionFactory sessions;
    private final SyncEngine syncEngine;

    private DesktopContext(AppPaths paths, MailStore store, CredentialStore credentialStore) {
        this.paths = paths;
        this.store = store;
        this.credentialStore = credentialStore;
        this.sessions = new AccountSessionFactory(credentialStore);
        this.syncEngine = new SyncEngine(store, sessions);
    }

    /**
     * Opens the application context.
     *
     * @param paths the data directory
     * @return the context
     * @throws Exception when the database or the credential store cannot be opened
     */
    public static DesktopContext open(AppPaths paths) throws Exception {
        java.nio.file.Files.createDirectories(paths.baseDirectory());
        CredentialStore credentials = DesktopSecretStore.open(paths);

        // Create the secret protector using the same passphrase/salt as the credential store
        char[] passphrase = resolvePassphraseForEncryption(paths);
        byte[] salt = readOrCreateSalt(paths.saltFile());
        SecretProtector protector = new com.graalmail.security.store.AesGcmProtector(passphrase, salt);
        java.util.Arrays.fill(passphrase, '\0');

        JdbcSqlDatabase baseDb = new JdbcSqlDatabase("jdbc:sqlite:" + paths.databaseFile());
        EncryptedSqlDatabase encryptedDb = new EncryptedSqlDatabase(baseDb, protector);
        SqlMailStore store = new SqlMailStore(encryptedDb);
        store.initialize();
        LOG.info("GraalMail data directory: {}", paths.baseDirectory());
        return new DesktopContext(paths, store, credentials);
    }

    private static char[] resolvePassphraseForEncryption(AppPaths paths) throws Exception {
        String fromEnvironment = System.getenv(DesktopSecretStore.PASSPHRASE_ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.toCharArray();
        }
        Path file = paths.masterPassphraseFile();
        if (java.nio.file.Files.exists(file)) {
            String stored = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!stored.isEmpty()) {
                return stored.toCharArray();
            }
        }
        // Should not happen if credential store was already opened
        throw new IllegalStateException("Master passphrase not available");
    }

    private static byte[] readOrCreateSalt(Path saltFile) throws java.io.IOException {
        if (java.nio.file.Files.exists(saltFile)) {
            byte[] salt = java.nio.file.Files.readAllBytes(saltFile);
            if (salt.length >= 16) {
                return salt;
            }
        }
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        java.nio.file.Files.createDirectories(saltFile.getParent());
        java.nio.file.Files.write(saltFile, salt, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        return salt;
    }

    public AppPaths paths() { return paths; }
    public MailStore store() { return store; }
    public CredentialStore credentialStore() { return credentialStore; }
    public AccountSessionFactory sessions() { return sessions; }
    public SyncEngine syncEngine() { return syncEngine; }

    /** The path of the local database file. */
    public Path databaseFile() {
        return paths.databaseFile();
    }

    @Override
    public void close() {
        try {
            store.close();
        } catch (RuntimeException e) {
            LOG.warn("The local database could not be closed cleanly: {}", e.getMessage());
        }
    }
}
