/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.gmail;

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
import com.graalmail.security.oauth.Pkce;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * The Gmail and Google Workspace provider.
 *
 * <p>GraalMail signs in with the email address and a password. Google rejects
 * account passwords for mail clients, so the Gmail provider uses an app
 * password: the user generates one in the Google account security settings
 * (which requires 2-Step Verification on the account) and enters it in
 * GraalMail. The mailbox is accessed over IMAP and SMTP with the plain
 * password mechanism.</p>
 *
 * <p>Accounts that were added with the earlier OAuth 2.0 sign in keep
 * working: their stored tokens are refreshed in the background. The OAuth
 * code below is retained for those accounts only; new accounts always sign
 * in with an app password.</p>
 */
public final class GmailProvider implements AccountProvider, OAuthCapableProvider {

    private static final Logger LOG = Log.of(GmailProvider.class);

    /** IMAP host of Gmail. */
    public static final String IMAP_HOST = "imap.gmail.com";
    /** IMAP port of Gmail. */
    public static final int IMAP_PORT = 993;
    /** SMTP host of Gmail. */
    public static final String SMTP_HOST = "smtp.gmail.com";
    /** SMTP port of Gmail. */
    public static final int SMTP_PORT = 587;

    @Override
    public ProviderType type() {
        return ProviderType.GMAIL;
    }

    @Override
    public String displayName() {
        return "Gmail / Google Workspace";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.builder(ProviderType.GMAIL)
                .authMethods(AuthMethod.PASSWORD, AuthMethod.APP_PASSWORD, AuthMethod.OAUTH2)
                .serverSideSearch(true)
                .pushNotifications(false)
                .idleSupport(true)
                .draftSync(true)
                .labels(true)
                .flags(true)
                .attachments(true)
                .unifiedInbox(true)
                .offlineActions(true)
                .serverSideFlags(true)
                .sendCapable(true)
                .platform("linux", true)
                .platform("android", true)
                .note("Gmail labels are mapped to mailboxes; user labels appear as custom folders.")
                .restriction("Google rejects account passwords for mail clients: GraalMail signs in "
                        + "with an app password, which requires 2-Step Verification on the account. "
                        + "Accounts added with the earlier OAuth sign in keep working with their "
                        + "stored tokens.")
                .documentationUrl("https://developers.google.com/gmail/imap/xoauth2-protocol")
                .build();
    }

    @Override
    public AuthorizationRequest beginAuthorization(AccountSetup setup) {
        OAuthConfig config = GmailOAuthConfig.forAccount(setup);
        AuthorizationRequest request = new OAuthClient(config).beginAuthorization();
        LOG.debug("Starting the Google authorization flow for {}", setup.email());
        return request;
    }

    @Override
    public AccountSetup completeAuthorization(AccountSetup setup, String code, String state,
                                              String receivedState, String codeVerifier) {
        if (state == null || receivedState == null || !state.equals(receivedState)) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "The authorization response did not match the request. Please try again.", false);
        }
        OAuthConfig config = GmailOAuthConfig.forAccount(setup);
        TokenSet tokens = new OAuthClient(config).exchangeCode(code, codeVerifier);
        LOG.debug("Google authorization completed for {}", setup.email());
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
     * Builds the IMAP configuration of a Gmail account.
     *
     * @param setup the account setup
     * @return the IMAP configuration
     */
    static ImapServerConfig imapConfig(AccountSetup setup) {
        return ImapServerConfig.builder()
                .host(IMAP_HOST)
                .port(IMAP_PORT)
                .securityMode(SecurityMode.SSL_TLS)
                .username(setup.email())
                .authMechanism(mechanism(setup))
                .connectTimeoutMs(15_000)
                .readTimeoutMs(30_000)
                .build();
    }

    /**
     * Builds the SMTP configuration of a Gmail account.
     *
     * @param setup the account setup
     * @return the SMTP configuration
     */
    static SmtpServerConfig smtpConfig(AccountSetup setup) {
        return SmtpServerConfig.builder()
                .host(SMTP_HOST)
                .port(SMTP_PORT)
                .securityMode(SecurityMode.STARTTLS)
                .username(setup.email())
                .authMechanism(mechanism(setup))
                .connectTimeoutMs(15_000)
                .readTimeoutMs(30_000)
                .build();
    }

    /** The auth mechanism of an account: OAuth for the legacy sign in, plain password otherwise. */
    private static AuthMethod mechanism(AccountSetup setup) {
        return setup.authMethod() == null ? AuthMethod.APP_PASSWORD : setup.authMethod();
    }

    /**
     * Builds the mailbox settings of a Gmail account. The labels of Gmail are
     * mapped to the neutral folder names.
     *
     * @param base the settings of the account
     * @return the settings including the label mapping
     */
    static Map<String, String> folderSettings(Map<String, String> base) {
        Map<String, String> settings = new HashMap<>(base);
        settings.put("sent.appendAfterSend", "true");
        settings.put("sent.folder", GmailFolderTypes.LABEL_SENT);
        settings.put("drafts.folder", GmailFolderTypes.LABEL_DRAFT);
        settings.put("trash.folder", GmailFolderTypes.LABEL_TRASH);
        settings.put("spam.folder", GmailFolderTypes.LABEL_SPAM);
        settings.put("inbox.folder", GmailFolderTypes.LABEL_INBOX);
        return settings;
    }

    @Override
    public ProviderSession openSession(Account account, AccountSetup setup) {
        if (setup.authMethod() == AuthMethod.OAUTH2) {
            return openOauthSession(account, setup);
        }
        return openPasswordSession(account, setup);
    }

    /** Signs in with the email address and a password or app password over plain IMAP and SMTP. */
    private ProviderSession openPasswordSession(Account account, AccountSetup setup) {
        String secret = setup.credential() == null ? "" : setup.credential().secret();
        if (secret.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "No password was provided. Google rejects account passwords for mail "
                            + "clients: generate an app password in the Google account security "
                            + "settings (this requires 2-Step Verification) and enter it here.",
                    false);
        }
        ImapServerConfig imapConfig = imapConfig(setup);
        SmtpServerConfig smtpConfig = smtpConfig(setup);
        Map<String, String> settings = folderSettings(setup.settings());
        ImapProviderSession session = new ImapProviderSession(
                account == null ? "" : account.id(), setup.email(), secret, imapConfig, smtpConfig,
                GmailFolderTypes.DEFAULT, settings, null);
        session.connect();
        return session;
    }

    /** Legacy path for accounts added with OAuth: refreshes the stored token and opens the session. */
    private ProviderSession openOauthSession(Account account, AccountSetup setup) {
        TokenSet tokens = setup.credential() == null ? TokenSet.empty()
                : setup.credential().tokens();
        OAuthConfig config = GmailOAuthConfig.forAccount(setup);
        TokenHolder holder = new TokenHolder(new OAuthClient(config), tokens);
        holder.refreshWhenNeeded();

        ImapServerConfig imapConfig = imapConfig(setup);
        SmtpServerConfig smtpConfig = smtpConfig(setup);
        Map<String, String> settings = folderSettings(setup.settings());

        ImapProviderSession session = new ImapProviderSession(
                account == null ? "" : account.id(), setup.email(), null, imapConfig, smtpConfig,
                GmailFolderTypes.DEFAULT, settings, holder::accessToken);
        session.connect();
        return session;
    }

    @Override
    public void revoke(AccountSetup setup) {
        if (setup.credential() == null) {
            return;
        }
        TokenSet tokens = setup.credential().tokens();
        if (!tokens.hasRefreshToken()) {
            return;
        }
        try {
            new OAuthClient(GmailOAuthConfig.forAccount(setup)).revoke(tokens.refreshToken());
            LOG.debug("Revoked the Google refresh token for {}", setup.email());
        } catch (RuntimeException e) {
            LOG.warn("The Google token could not be revoked remotely: {}", e.getMessage());
        }
    }

    /**
     * Creates a PKCE verifier for a manual authorization flow.
     *
     * @return a new code verifier
     */
    public static String newCodeVerifier() {
        return Pkce.newCodeVerifier();
    }

    /**
     * Holds the tokens of one account and refreshes them when they expire.
     */
    private static final class TokenHolder {

        private final OAuthClient client;
        private TokenSet tokens;

        private TokenHolder(OAuthClient client, TokenSet tokens) {
            this.client = client;
            this.tokens = tokens == null ? TokenSet.empty() : tokens;
        }

        private void refreshWhenNeeded() {
            if (tokens.accessToken().isBlank()) {
                throw ProviderException.authExpired(
                        "No Google access token available, please sign in again.");
            }
            if (tokens.isExpired(Instant.now(), 60) && tokens.hasRefreshToken()) {
                tokens = client.refresh(tokens.refreshToken());
            }
        }

        private String accessToken() {
            if (tokens.isExpired(Instant.now(), 60) && tokens.hasRefreshToken()) {
                tokens = client.refresh(tokens.refreshToken());
            }
            if (tokens.accessToken().isBlank()) {
                throw ProviderException.authExpired(
                        "No Google access token available, please sign in again.");
            }
            return tokens.accessToken();
        }
    }
}
