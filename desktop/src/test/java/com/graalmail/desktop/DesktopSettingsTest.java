/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.SqlMailStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DesktopSettingsTest {

    private SqlMailStore store;
    private DesktopSettings settings;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        settings = new DesktopSettings(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void usesTheDefaultsUntilAValueIsStored() {
        assertEquals(DesktopSettings.DEFAULT_SYNC_INTERVAL_MINUTES, settings.syncIntervalMinutes());
        assertEquals(DesktopSettings.DEFAULT_PAGE_SIZE, settings.pageSize());
        assertTrue(settings.notificationsEnabled());
    }

    @Test
    void storesTheValuesInTheLocalDatabase() {
        settings.syncIntervalMinutes(30);
        settings.pageSize(50);
        settings.notificationsEnabled(false);
        settings.lastAccountId("account-1");

        DesktopSettings reopened = new DesktopSettings(store);
        assertEquals(30, reopened.syncIntervalMinutes());
        assertEquals(50, reopened.pageSize());
        assertFalse(reopened.notificationsEnabled());
        assertEquals("account-1", reopened.lastAccountId());
    }

    @Test
    void rejectsInvalidValues() {
        settings.syncIntervalMinutes(0);
        settings.pageSize(1);
        assertEquals(1, settings.syncIntervalMinutes());
        assertEquals(20, settings.pageSize());
    }
}
