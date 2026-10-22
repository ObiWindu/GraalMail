/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.storage.MailStore;

/**
 * The user settings of the desktop application.
 *
 * <p>The settings are stored in the local database so that they survive a
 * restart and can be backed up together with the mail cache.</p>
 */
public final class DesktopSettings {

    /** Key of the automatic synchronisation interval in minutes. */
    public static final String SYNC_INTERVAL_MINUTES = "desktop.syncIntervalMinutes";
    /** Key of the desktop notification switch. */
    public static final String NOTIFICATIONS_ENABLED = "desktop.notificationsEnabled";
    /** Key of the number of messages that are loaded per page. */
    public static final String PAGE_SIZE = "desktop.pageSize";
    /** Key of the last used account identifier. */
    public static final String LAST_ACCOUNT = "desktop.lastAccountId";

    /** The default synchronisation interval in minutes. */
    public static final int DEFAULT_SYNC_INTERVAL_MINUTES = 15;
    /** The default number of messages per page. */
    public static final int DEFAULT_PAGE_SIZE = 200;

    private final MailStore store;

    /**
     * Creates the settings facade.
     *
     * @param store the local database
     */
    public DesktopSettings(MailStore store) {
        this.store = store;
    }

    /**
     * The automatic synchronisation interval in minutes.
     *
     * @return the interval, never below one minute
     */
    public int syncIntervalMinutes() {
        return positive(store.setting(SYNC_INTERVAL_MINUTES, ""),
                DEFAULT_SYNC_INTERVAL_MINUTES);
    }

    /**
     * Stores the automatic synchronisation interval.
     *
     * @param minutes the interval in minutes
     */
    public void syncIntervalMinutes(int minutes) {
        store.putSetting(SYNC_INTERVAL_MINUTES, Integer.toString(Math.max(1, minutes)));
    }

    /**
     * Whether desktop notifications are shown.
     *
     * @return {@code true} when notifications are enabled
     */
    public boolean notificationsEnabled() {
        return Boolean.parseBoolean(store.setting(NOTIFICATIONS_ENABLED, "true"));
    }

    /**
     * Switches the desktop notifications on or off.
     *
     * @param enabled {@code true} to show notifications
     */
    public void notificationsEnabled(boolean enabled) {
        store.putSetting(NOTIFICATIONS_ENABLED, Boolean.toString(enabled));
    }

    /**
     * The number of messages that are loaded per page.
     *
     * @return the page size
     */
    public int pageSize() {
        return positive(store.setting(PAGE_SIZE, ""), DEFAULT_PAGE_SIZE);
    }

    /**
     * Stores the number of messages that are loaded per page.
     *
     * @param size the page size
     */
    public void pageSize(int size) {
        store.putSetting(PAGE_SIZE, Integer.toString(Math.max(20, size)));
    }

    /**
     * The identifier of the account that was used last.
     *
     * @return the account identifier or an empty string
     */
    public String lastAccountId() {
        return store.setting(LAST_ACCOUNT, "");
    }

    /**
     * Stores the identifier of the account that was used last.
     *
     * @param accountId the account identifier
     */
    public void lastAccountId(String accountId) {
        store.putSetting(LAST_ACCOUNT, accountId == null ? "" : accountId);
    }

    private static int positive(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
