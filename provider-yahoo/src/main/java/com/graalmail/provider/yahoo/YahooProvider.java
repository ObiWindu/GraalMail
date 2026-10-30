/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.yahoo;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Log;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.OAuthCapableProvider;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.imap.ImapProviderSession;
import com.graalmail.provider.imap.ImapServerConfig;
import com.graalmail.provider.imap.SecurityMode;
import com.graalmail.provider.imap.SmtpServerConfig;
import com.graalmail.security.model.TokenSet;
import com.graalmail.security.oauth.AuthorizationRequest;
import com.graalmail.security.oauth.OAuthClient;
import com.graalmail.security.oauth.OAuthConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * The Yahoo Mail provider.
 *
 * <p>Yahoo rejects the account password for third party clients. Sign in uses
 * an app password generated in the Yahoo account security settings; it is
 * used with IMAP and SMTP over TLS. GraalMail signs in with a password only,
 * so the account password is never requested. The OAuth 2.0 flow exists in
 * the provider for compatibility, but Yahoo only grants the mail scopes to
 * approved applications and no user interface offers it.</p>
 */
public final class YahooProvider implements AccountProvider, OAuthCapableProvider {

    private static final Logger LOG = Log.of(YahooProvider.class);

    /** IMAP host of Yahoo Mail. */
    public static final String IMAP_HOST = "imap.mail.yahoo.com";
    /** IMAP port of Yahoo Mail. */
    public static final int IMAP_PORT = 993;
    /** SMTP host of Yahoo Mail. */
    public static final String SMTP_HOST = "smtp.mail.yahoo.com";
    /** SMTP submission port of Yahoo Mail. */
    public static final int SMTP_PORT = 465;
    /** Alternative SMTP submission port of Yahoo Mail. */
    public static final int SMTP_PORT_STARTTLS = 587;

    /** Authorization endpoint of Yahoo. */
    public static final String AUTHORIZATION_ENDPOINT =
            "https://api.login.yahoo.com/oauth2/request_auth";
    /** Token endpoint of Yahoo. */
    public static final String TOKEN_ENDPOINT = "https://api.login.yahoo.com/oauth2/get_token";
    /** Revocation endpoint of Yahoo. */
    public static final String REVOKE_ENDPOINT = "https://api.login.yahoo.com/oauth2/revoke";

    @Override
    public ProviderType type() {
        return ProviderType.YAHOO;
    }

    @Override
    public String displayName() {
        return "Yahoo Mail";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.YAHOO)
                .authMethods(AuthMethod.APP_PASSWORD, AuthMethod.OAUTH2)
                .serverSideSearch(true)
                .pushNotifications(false)
                .idleSupport(true)
                .draftSync(true)
                .labels(false)
                .flags(true)
                .attachments(true)
                .unifiedInbox(true)
                .offlineActions(true)
                .serverSideFlags(true)
                .sendCapable(true)
                .platform("linux", true)
                .platform("android", true)
                .note("Sign in uses a Yahoo app password unless a Yahoo OAuth client was approved.")
                .restriction("Yahoo requires an app password; the account password is rejected.")
                .documentationUrl("https://help.yahoo.com/kb/SLN15241.html")
                .build();
    }

    @Override
    public AuthorizationRequest beginAuthorization(AccountSetup setup) {
        OAuthConfig config = YahooOAuthConfig.forAccount(setup);
        return new OAuthClient(config).beginAuthorization();
    }

    @Override
    public AccountSetup completeAuthorization(AccountSetup setup, String code, String state,
                                              String receivedState, String codeVerifier) {
        if (state == null || receivedState == null || !state.equals(receivedState)) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "The authorization response did not match the request. Please try again.", false);
        }
        OAuthConfig config = YahooOAuthConfig.forAccount(setup);
        TokenSet tokens = new OAuthClient(config).exchangeCode(code, codeVerifier);
        LOG.debug("Yahoo authorization completed for {}", setup.email());
        return AccountSetup.builder(AuthMethod.OAUTH2)
                .email(setup.email())
                .displayName(setup.displayName())
                .credential(com.graalmail.security.model.Credential.oauth(setup.email(), tokens))
                .settings(setup.settings())
                .build();
    }

    @Override
    public ProviderSession openSession(AccountSetup setup) {
        return openSession(null, setup);
    }


    /**
     * Builds the IMAP configuration of a Yahoo account.
     *
     * @param setup the account setup
     * @param oauth {@code true} when the account signs in with OAuth
     * @return the IMAP configuration
     */
    static ImapServerConfig imapConfig(AccountSetup setup, boolean oauth) {
        return ImapServerConfig.builder()
                .host(IMAP_HOST)
                .port(IMAP_PORT)
                .securityMode(SecurityMode.SSL_TLS)
                .username(setup.email())
                .authMechanism(oauth ? AuthMethod.OAUTH2 : AuthMethod.APP_PASSWORD)
                .connectTimeoutMs(15_000)
                .readTimeoutMs(30_000)
                .build();
    }

    /**
     * Builds the SMTP configuration of a Yahoo account.
     *
     * @param setup the account setup
     * @param oauth {@code true} when the account signs in with OAuth
     * @return the SMTP configuration
     */
    static SmtpServerConfig smtpConfig(AccountSetup setup, boolean oauth) {
        return SmtpServerConfig.builder()
                .host(SMTP_HOST)
                .port(SMTP_PORT)
                .securityMode(SecurityMode.SSL_TLS)
                .username(setup.email())
                .authMechanism(oauth ? AuthMethod.OAUTH2 : AuthMethod.APP_PASSWORD)
                .connectTimeoutMs(15_000)
                .readTimeoutMs(30_000)
                .build();
    }

    /**
     * Builds the mailbox settings of a Yahoo account.
     *
     * @param base the settings of the account
     * @return the settings including the sent folder behaviour
     */
    static Map<String, String> folderSettings(Map<String, String> base) {
        Map<String, String> settings = new HashMap<>(base);
        settings.put("sent.appendAfterSend", "true");
        return settings;
    }

    @Override
    public ProviderSession openSession(Account account, AccountSetup setup) {
        if (setup.email().isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The email address of the Yahoo account is missing.", false);
        }
        boolean oauth = setup.authMethod() == AuthMethod.OAUTH2;
        String secret;
        if (oauth) {
            secret = currentToken(setup);
        } else {
            secret = setup.credential() == null ? "" : setup.credential().secret();
            if (secret.isBlank()) {
                throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                        "No Yahoo app password was provided. Generate one in the Yahoo account "
                                + "security settings.", false);
            }
        }

        ImapServerConfig imapConfig = imapConfig(setup, oauth);
        SmtpServerConfig smtpConfig = smtpConfig(setup, oauth);

        Map<String, String> settings = folderSettings(setup.settings());
        ImapProviderSession session = new ImapProviderSession(
                account == null ? "" : account.id(), setup.email(), secret, imapConfig, smtpConfig,
                com.graalmail.provider.imap.ImapFolderTypes.DEFAULT, settings,
                oauth ? () -> currentToken(setup) : null);
        session.connect();
        return session;
    }

    @Override
    public void revoke(AccountSetup setup) {
        if (setup.credential() == null || setup.authMethod() != AuthMethod.OAUTH2) {
            return;
        }
        TokenSet tokens = setup.credential().tokens();
        if (!tokens.hasRefreshToken()) {
            return;
        }
        try {
            new OAuthClient(YahooOAuthConfig.forAccount(setup)).revoke(tokens.refreshToken());
        } catch (RuntimeException e) {
            LOG.warn("The Yahoo token could not be revoked remotely: {}", e.getMessage());
        }
    }

    private static String currentToken(AccountSetup setup) {
        TokenSet tokens = setup.credential() == null ? TokenSet.empty() : setup.credential().tokens();
        if (tokens.accessToken().isBlank()) {
            throw ProviderException.authExpired(
                    "No Yahoo access token available, please sign in again.");
        }
        if (tokens.isExpired(Instant.now(), 60) && tokens.hasRefreshToken()) {
            return new OAuthClient(YahooOAuthConfig.forAccount(setup))
                    .refresh(tokens.refreshToken()).accessToken();
        }
        return tokens.accessToken();
    }
}
