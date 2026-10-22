/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import java.util.Map;

/**
 * The account setting keys understood by the IMAP provider.
 */
public final class ImapSettings {

    public static final String IMAP_HOST = "imap.host";
    public static final String IMAP_PORT = "imap.port";
    public static final String IMAP_SECURITY = "imap.security";
    public static final String SMTP_HOST = "smtp.host";
    public static final String SMTP_PORT = "smtp.port";
    public static final String SMTP_SECURITY = "smtp.security";
    public static final String POP3_HOST = "pop3.host";
    public static final String POP3_PORT = "pop3.port";
    public static final String POP3_SECURITY = "pop3.security";
    public static final String USERNAME = "auth.username";
    public static final String AUTH_MECHANISM = "auth.mechanism";
    public static final String CLIENT_CERTIFICATE = "imap.clientCert";
    public static final String CLIENT_CERTIFICATE_PASSWORD = "imap.clientCertPassword";
    public static final String TRUST_CERTIFICATE = "imap.trustCert";
    public static final String CONNECT_TIMEOUT = "connect.timeoutMs";
    public static final String READ_TIMEOUT = "read.timeoutMs";
    public static final String ATTACHMENT_MAX_BYTES = "attachment.maxBytes";
    public static final String SPAM_FOLDER = "spam.folder";
    public static final String DRAFTS_FOLDER = "drafts.folder";
    public static final String SENT_FOLDER = "sent.folder";
    public static final String TRASH_FOLDER = "trash.folder";
    public static final String INBOX_FOLDER = "inbox.folder";

    /** Default upper bound for a single attachment. */
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private ImapSettings() {
    }

    /**
     * Reads an integer setting.
     *
     * @param settings the settings map
     * @param key the setting key
     * @param fallback the value used when the key is missing or not a number
     * @return the resolved value
     */
    public static int intValue(Map<String, String> settings, String key, int fallback) {
        String raw = settings == null ? null : settings.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a long setting.
     *
     * @param settings the settings map
     * @param key the setting key
     * @param fallback the value used when the key is missing or not a number
     * @return the resolved value
     */
    public static long longValue(Map<String, String> settings, String key, long fallback) {
        String raw = settings == null ? null : settings.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a trimmed setting.
     *
     * @param settings the settings map
     * @param key the setting key
     * @param fallback the value used when the key is missing or blank
     * @return the resolved value
     */
    public static String text(Map<String, String> settings, String key, String fallback) {
        String raw = settings == null ? null : settings.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim();
    }
}
