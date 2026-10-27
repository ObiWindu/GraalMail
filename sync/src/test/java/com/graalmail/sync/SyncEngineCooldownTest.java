/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.security.store.CredentialStore;
import com.graalmail.security.store.InMemoryCredentialStore;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.SqlMailStore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A limited account must not be hammered: the first failure starts a
 * cooldown, further syncs skip it without touching the network, and a
 * recovered account resumes on the next sync.
 */
class SyncEngineCooldownTest {

    private MailStore store;
    private SyncEngine syncEngine;
    private AtomicInteger opens;
    private boolean limited;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        store.saveAccount(Account.builder("a1", "test").email("test@example.com").build());
        opens = new AtomicInteger();
        limited = true;
        CredentialStore credentialStore = new InMemoryCredentialStore();
        AccountSessionFactory sessionFactory = new AccountSessionFactory(credentialStore) {
            @Override
            public ProviderSession open(Account account) {
                opens.incrementAndGet();
                if (limited) {
                    throw new ProviderException(ErrorCode.ACCOUNT_LIMITED,
                            "Proton has temporarily limited access to this account.", false);
                }
                throw new ProviderException(ErrorCode.CONNECTION_REFUSED, "unused", true);
            }
        };
        syncEngine = new SyncEngine(store, sessionFactory);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void limitedAccountStartsCooldownAndSkipsNetwork() {
        var first = syncEngine.syncAccount("a1");
        assertEquals(1, opens.get());
        assertTrue(first.errors().stream().anyMatch(error -> error.contains("temporarily limited")));
        assertTrue(first.errors().stream().anyMatch(error -> error.contains("resumes automatically")));
        assertEquals(0, first.newMessages());

        var second = syncEngine.syncAccount("a1");
        assertEquals(1, opens.get(), "sync while limited must not touch the network");
        assertEquals(0, second.newMessages());
        assertTrue(second.errors().stream().anyMatch(error -> error.contains("paused until")));
    }

    @Test
    void recoveredAccountResumesImmediately() {
        syncEngine.syncAccount("a1");
        assertEquals(1, opens.get());

        limited = false;
        store.putSetting("sync.cooldownUntil.a1", "");
        var retried = syncEngine.syncAccount("a1");
        assertEquals(2, opens.get());
        assertTrue(retried.errors().stream().anyMatch(error -> error.contains("unused")));
    }

    @Test
    void staleCooldownSkipsUntilCleared() {
        store.putSetting("sync.cooldownUntil.a1", "2030-01-01T00:00:00Z");
        var skipped = syncEngine.syncAccount("a1");
        assertEquals(0, opens.get());
        assertTrue(skipped.errors().stream().anyMatch(error -> error.contains("paused until")));
    }
}
