/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import com.graalmail.security.store.CredentialStore;
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
        JdbcSqlDatabase database = new JdbcSqlDatabase("jdbc:sqlite:" + paths.databaseFile());
        SqlMailStore store = new SqlMailStore(database);
        store.initialize();
        CredentialStore credentials = DesktopSecretStore.open(paths);
        LOG.info("GraalMail data directory: {}", paths.baseDirectory());
        return new DesktopContext(paths, store, credentials);
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
