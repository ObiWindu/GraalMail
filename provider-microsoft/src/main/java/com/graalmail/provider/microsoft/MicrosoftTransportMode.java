/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ProviderException;
import java.util.Locale;

/**
 * The transport used to talk to a Microsoft mailbox.
 *
 * <p>{@link #IMAP} uses the IMAP and SMTP protocol endpoints with XOAUTH2,
 * {@link #GRAPH} uses the Microsoft Graph web API. Both modes implement the
 * same provider-api interfaces.</p>
 */
public enum MicrosoftTransportMode {

    /** IMAP (outlook.office365.com) and SMTP (smtp.office365.com) with XOAUTH2. */
    IMAP,
    /** Microsoft Graph web API. */
    GRAPH;

    /** Setting key that selects the transport. */
    public static final String SETTING = "transport";

    /**
     * Resolves the transport from a setting value.
     *
     * @param value the raw setting value, may be {@code null}
     * @return the resolved mode, defaulting to {@link #IMAP}
     * @throws ProviderException with {@code CONFIGURATION_ERROR} for unknown values
     */
    public static MicrosoftTransportMode fromSetting(String value) {
        if (value == null || value.isBlank()) {
            return IMAP;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("imap") || normalized.equals("protocol")) {
            return IMAP;
        }
        if (normalized.equals("graph")) {
            return GRAPH;
        }
        throw ProviderException.configuration(
                "Unknown transport '" + value + "'; expected 'imap' or 'graph'");
    }
}
