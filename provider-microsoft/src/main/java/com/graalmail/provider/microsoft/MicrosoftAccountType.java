/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import java.util.Locale;
import java.util.Set;

/**
 * Distinguishes Microsoft consumer ("personal") accounts from organizational
 * Microsoft 365 or Exchange Online accounts.
 *
 * <p>Personal accounts live in the {@code consumers} tenant and are reachable
 * through the {@code common} authority. Organizational accounts belong to a
 * directory tenant and need a tenant specific authority plus administrator
 * consent for the IMAP and SMTP delegated permissions.</p>
 */
public enum MicrosoftAccountType {

    /** Outlook.com, Hotmail, Live.com or MSN consumer account. */
    PERSONAL,
    /** Organizational account hosted in a Microsoft 365 or Exchange Online tenant. */
    ORGANIZATIONAL;

    /** Domains that are always treated as consumer accounts. */
    public static final Set<String> PERSONAL_DOMAINS =
            Set.of("outlook.com", "hotmail.com", "live.com", "msn.com");

    /** The tenant identifier that serves consumer accounts. */
    public static final String CONSUMERS_TENANT = "consumers";
    /** The tenant identifier that serves both consumer and organizational accounts. */
    public static final String COMMON_TENANT = "common";

    /**
     * Detects the account type from an email address.
     *
     * @param email the account email address, may be {@code null}
     * @return {@link #PERSONAL} for known consumer domains, otherwise
     *         {@link #ORGANIZATIONAL}
     */
    public static MicrosoftAccountType detectFromEmail(String email) {
        if (email == null) {
            return ORGANIZATIONAL;
        }
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? email : email.substring(at + 1);
        return detectFromDomain(domain);
    }

    /**
     * Detects the account type from a bare domain name.
     *
     * @param domain the domain part of an email address
     * @return {@link #PERSONAL} for known consumer domains, otherwise
     *         {@link #ORGANIZATIONAL}
     */
    public static MicrosoftAccountType detectFromDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return ORGANIZATIONAL;
        }
        String value = domain.trim().toLowerCase(Locale.ROOT);
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (PERSONAL_DOMAINS.contains(value)) {
            return PERSONAL;
        }
        if (value.startsWith("hotmail.") || value.startsWith("live.") || value.startsWith("msn.")) {
            return PERSONAL;
        }
        return ORGANIZATIONAL;
    }

    /**
     * Detects the account type from an Azure tenant identifier.
     *
     * @param tenant the tenant identifier, for example {@code common}
     * @return {@link #PERSONAL} for {@code consumers}, {@link #PERSONAL} for
     *         {@code common} (which also serves consumer accounts) and
     *         {@link #ORGANIZATIONAL} for every other tenant
     */
    public static MicrosoftAccountType detectFromTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return PERSONAL;
        }
        String value = tenant.trim().toLowerCase(Locale.ROOT);
        if (value.equals(CONSUMERS_TENANT) || value.equals(COMMON_TENANT)) {
            return PERSONAL;
        }
        return ORGANIZATIONAL;
    }

    /** True when this is a consumer account. */
    public boolean isPersonal() {
        return this == PERSONAL;
    }

    /** True when this is an organizational account. */
    public boolean isOrganizational() {
        return this == ORGANIZATIONAL;
    }
}
