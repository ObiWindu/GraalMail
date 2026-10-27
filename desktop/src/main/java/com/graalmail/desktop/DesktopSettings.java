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
    public static final String LAST_FOLDER = "desktop.lastFolderId";
    public static final String COMPACT_DENSITY = "desktop.compactDensity";
    public static final String THEME = "desktop.theme";
    public static final String SHOW_NAVIGATION = "desktop.showNavigation";
    public static final String SHOW_READER = "desktop.showReader";
    public static final String LAYOUT_MODE = "desktop.layoutMode";
    public static final String LIST_MODE = "desktop.listMode";

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

    public String lastFolderId() {
        return store.setting(LAST_FOLDER, "");
    }

    public void lastFolderId(String folderId) {
        store.putSetting(LAST_FOLDER, folderId == null ? "" : folderId);
    }

    public double paneDivider(int index, double fallback) {
        return paneDivider(layoutMode(), index, fallback);
    }

    public double paneDivider(String layout, int index, double fallback) {
        try {
            return Double.parseDouble(store.setting("desktop.paneDivider." + layout + "." + index,
                    Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void setPaneDivider(int index, double value) {
        setPaneDivider(layoutMode(), index, value);
    }

    public void setPaneDivider(String layout, int index, double value) {
        store.putSetting("desktop.paneDivider." + layout + "." + index, Double.toString(value));
    }

    public boolean compactDensity() {
        return Boolean.parseBoolean(store.setting(COMPACT_DENSITY, "false"));
    }

    public void compactDensity(boolean compact) {
        store.putSetting(COMPACT_DENSITY, Boolean.toString(compact));
    }

    public String theme() {
        String value = store.setting(THEME, "System");
        return "Dark".equalsIgnoreCase(value) ? "Dark"
                : "System".equalsIgnoreCase(value) ? "System" : "Light";
    }

    public boolean darkTheme() {
        String selected = theme();
        if ("Dark".equals(selected)) return true;
        if ("Light".equals(selected)) return false;
        String override = System.getProperty("graalmail.theme", "");
        if (!override.isBlank()) return "dark".equalsIgnoreCase(override);
        String gtk = System.getenv("GTK_THEME");
        String appearance = System.getProperty("apple.awt.application.appearance", "");
        if (gtk != null && gtk.toLowerCase(java.util.Locale.ROOT).contains("dark")) return true;
        if (appearance.toLowerCase(java.util.Locale.ROOT).contains("dark")) return true;
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            try {
                return java.util.prefs.Preferences.userRoot()
                        .node("Software/Microsoft/Windows/CurrentVersion/Themes/Personalize")
                        .getInt("AppsUseLightTheme", 1) == 0;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    public void theme(String value) {
        store.putSetting(THEME, "Dark".equalsIgnoreCase(value) ? "Dark"
                : "System".equalsIgnoreCase(value) ? "System" : "Light");
    }

    public boolean showNavigation() {
        return Boolean.parseBoolean(store.setting(SHOW_NAVIGATION, "true"));
    }

    public void showNavigation(boolean visible) {
        store.putSetting(SHOW_NAVIGATION, Boolean.toString(visible));
    }

    public boolean showReader() {
        return Boolean.parseBoolean(store.setting(SHOW_READER, "true"));
    }

    /** The workspace arrangement: Classic, Wide, or Vertical. */
    public String layoutMode() {
        String value = store.setting(LAYOUT_MODE, "Vertical");
        return switch (value) {
            case "Classic", "Wide", "Vertical" -> value;
            default -> "Vertical";
        };
    }

    public void layoutMode(String value) {
        store.putSetting(LAYOUT_MODE, switch (value == null ? "" : value) {
            case "Classic", "Wide" -> value;
            default -> "Vertical";
        });
    }

    public String listMode() {
        return "Table".equalsIgnoreCase(store.setting(LIST_MODE, "Cards")) ? "Table" : "Cards";
    }

    public void listMode(String value) {
        store.putSetting(LIST_MODE, "Table".equalsIgnoreCase(value) ? "Table" : "Cards");
    }

    public void showReader(boolean visible) {
        store.putSetting(SHOW_READER, Boolean.toString(visible));
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
