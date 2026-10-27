/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import java.util.Locale;
import java.util.Set;

/**
 * Deduces the mail provider from the account email domain so setup
 * dialogs do not need a provider menu in the normal flow.
 */
public final class ProviderDeduction {

    private static final Set<String> GMAIL_NAMES = Set.of("gmail", "googlemail");
    private static final Set<String> MICROSOFT_NAMES = Set.of("outlook", "hotmail", "live", "msn");
    private static final Set<String> YAHOO_NAMES = Set.of("yahoo", "ymail");
    private static final Set<String> PROTON_NAMES = Set.of("proton", "protonmail", "pm");
    /** Multi-label public suffixes whose registrable domain keeps three labels. */
    private static final Set<String> MULTI_SUFFIX = Set.of("co.uk", "org.uk", "me.uk",
            "com.br", "co.jp", "ne.jp", "com.au", "net.au", "co.in", "co.nz", "com.mx",
            "com.ar", "com.sg", "com.hk", "co.za");

    private ProviderDeduction() {
    }

    /**
     * Deduces the provider from an email address.
     *
     * @param email the account email, may be blank
     * @return the deduced provider, {@link ProviderType#GENERIC} when the
     *         domain is unknown or custom (hosts its own IMAP server)
     */
    public static ProviderType deduceProvider(String email) {
        String name = baseName(domainOf(email));
        if (name.isEmpty()) {
            return ProviderType.GENERIC;
        }
        if (GMAIL_NAMES.contains(name)) {
            return ProviderType.GMAIL;
        }
        if (MICROSOFT_NAMES.contains(name)) {
            return ProviderType.MICROSOFT;
        }
        if (YAHOO_NAMES.contains(name)) {
            return ProviderType.YAHOO;
        }
        if (PROTON_NAMES.contains(name)) {
            return ProviderType.PROTON;
        }
        return ProviderType.GENERIC;
    }

    private static String domainOf(String email) {
        if (email == null) {
            return "";
        }
        String trimmed = email.strip().toLowerCase(Locale.ROOT);
        int at = trimmed.lastIndexOf('@');
        if (at < 0 || at == trimmed.length() - 1) {
            return "";
        }
        String domain = trimmed.substring(at + 1);
        if (domain.startsWith(".") || domain.endsWith(".") || domain.contains(" ")) {
            return "";
        }
        return domain;
    }

    /**
     * The registrable second-level name: {@code hotmail} for both
     * {@code hotmail.com} and {@code hotmail.co.uk}, so regional variants
     * match while lookalikes such as {@code hotmail.evil.ch} do not.
     */
    static String baseName(String domain) {
        if (domain.isEmpty()) {
            return "";
        }
        String[] labels = domain.split("\\.");
        int keep = 2;
        if (labels.length >= 3
                && MULTI_SUFFIX.contains(labels[labels.length - 2] + "." + labels[labels.length - 1])) {
            keep = 3;
        }
        if (labels.length < keep) {
            return "";
        }
        return labels[labels.length - keep];
    }
}
