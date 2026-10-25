/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import java.util.Map;

/**
 * Builds the IMAP and SMTP configuration of an account from the stored
 * settings.
 */
public final class ImapAccountFactory {

    private ImapAccountFactory() {
    }

    /**
     * Creates the IMAP configuration of an account.
     *
     * @param setup the account setup
     * @return the configuration
     */
    public static ImapServerConfig imapConfig(AccountSetup setup) {
        Map<String, String> settings = setup.settings();
        AuthMethod mechanism = mechanism(setup);
        String username = ImapSettings.text(settings, ImapSettings.USERNAME, setup.email());
        int port = ImapSettings.intValue(settings, ImapSettings.IMAP_PORT,
                ImapServerConfig.DEFAULT_PORT);
        SecurityMode mode = SecurityMode.parse(settings.get(ImapSettings.IMAP_SECURITY));
        return ImapServerConfig.builder()
                .host(ImapSettings.text(settings, ImapSettings.IMAP_HOST, ""))
                .port(port)
                .securityMode(mode)
                .username(username)
                .authMechanism(mechanism)
                .connectTimeoutMs(ImapSettings.intValue(settings, ImapSettings.CONNECT_TIMEOUT, 15_000))
                .readTimeoutMs(ImapSettings.intValue(settings, ImapSettings.READ_TIMEOUT, 30_000))
                .build();
    }

    /**
     * Creates the SMTP configuration of an account.
     *
     * @param setup the account setup
     * @return the configuration
     */
    public static SmtpServerConfig smtpConfig(AccountSetup setup) {
        Map<String, String> settings = setup.settings();
        AuthMethod mechanism = mechanism(setup);
        String username = ImapSettings.text(settings, ImapSettings.USERNAME, setup.email());
        int port = ImapSettings.intValue(settings, ImapSettings.SMTP_PORT,
                SmtpServerConfig.DEFAULT_PORT);
        SecurityMode mode = SecurityMode.parse(settings.get(ImapSettings.SMTP_SECURITY));
        return SmtpServerConfig.builder()
                .host(ImapSettings.text(settings, ImapSettings.SMTP_HOST, ""))
                .port(port)
                .securityMode(mode)
                .username(username)
                .authMechanism(mechanism)
                .connectTimeoutMs(ImapSettings.intValue(settings, ImapSettings.CONNECT_TIMEOUT, 15_000))
                .readTimeoutMs(ImapSettings.intValue(settings, ImapSettings.READ_TIMEOUT, 30_000))
                .build();
    }

    /**
     * The plain password of an account, if the credential holds one.
     *
     * @param setup the account setup
     * @return the password or {@code null}
     */
    public static String password(AccountSetup setup) {
        if (setup.credential() == null) {
            return null;
        }
        return setup.credential().secret();
    }

    private static AuthMethod mechanism(AccountSetup setup) {
        AuthMethod configured = setup.authMethod();
        if (configured == null) {
            return AuthMethod.PASSWORD;
        }
        return configured;
    }

    /**
     * Validates that the account carries the settings a server needs.
     *
     * @param setup the account setup
     */
    public static void validate(AccountSetup setup) {
        if (setup == null) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The account configuration is missing.", false);
        }
        if (setup.email().isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The email address of the account is missing.", false);
        }
        if (setup.authMethod() == AuthMethod.OAUTH2) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The generic IMAP provider does not support OAuth sign in.", false);
        }
    }
}
