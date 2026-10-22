/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.util.Locale;

/**
 * Transport security of a mail connection.
 *
 * <p>{@link #SSL_TLS} is implicit TLS (the TLS handshake happens before the
 * protocol greeting), {@link #STARTTLS} upgrades a plain connection, and
 * {@link #NONE} means the connection is not encrypted at all.</p>
 */
public enum SecurityMode {

    /** No transport encryption. Only acceptable for loopback connections. */
    NONE,
    /** Implicit TLS on the connection, usually port 465 or 993. */
    SSL_TLS,
    /** Explicit TLS upgrade after the protocol greeting, usually port 143 or 587. */
    STARTTLS;

    /**
     * Parses a user supplied security mode.
     *
     * @param value the raw setting value, may be {@code null}
     * @return the parsed mode or {@code null} when the value is blank
     * @throws ProviderException when the value is not understood
     */
    public static SecurityMode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "NONE", "PLAIN", "OFF" -> NONE;
            case "SSL", "SSL_TLS", "TLS", "IMAPS", "SMTPS", "IMPLICIT" -> SSL_TLS;
            case "STARTTLS", "START_TLS" -> STARTTLS;
            default -> throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Unknown security mode '" + value + "'. Use none, ssl or starttls.", false);
        };
    }

    /** The canonical textual form used in settings files. */
    public String settingValue() {
        return name();
    }
}
