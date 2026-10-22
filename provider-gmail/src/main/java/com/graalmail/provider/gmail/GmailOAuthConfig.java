/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.gmail;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.security.oauth.OAuthConfig;
import java.util.List;

/**
 * Builds the {@link OAuthConfig} for Google.
 *
 * <p>The client id is never hard coded. It is read from the account setting
 * {@code oauth.clientId} so that a distribution can ship its own OAuth client
 * without changing the code. A client secret is optional: desktop and Android
 * public clients use PKCE and do not need one.</p>
 */
public final class GmailOAuthConfig {

    /** Authorization endpoint of Google. */
    public static final String AUTHORIZATION_ENDPOINT =
            "https://accounts.google.com/o/oauth2/v2/auth";
    /** Token endpoint of Google. */
    public static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    /** Revocation endpoint of Google. */
    public static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";

    /** Full access to the mailbox, required for IMAP and SMTP with OAuth. */
    public static final String SCOPE_MAIL = "https://mail.google.com/";
    /** Read only access to the mailbox. */
    public static final String SCOPE_MAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    /** Send only access to the mailbox. */
    public static final String SCOPE_MAIL_SEND = "https://www.googleapis.com/auth/gmail.send";
    /** Read and modify access to the mailbox. */
    public static final String SCOPE_MAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify";
    /** Access to the address of the signed in user. */
    public static final String SCOPE_USERINFO_EMAIL = "https://www.googleapis.com/auth/userinfo.email";
    /** OpenID Connect scope, used together with the email scope. */
    public static final String SCOPE_OPENID = "openid";

    private static final List<String> DEFAULT_SCOPES =
            List.of(SCOPE_MAIL, SCOPE_USERINFO_EMAIL, SCOPE_OPENID);

    private GmailOAuthConfig() {
    }

    /**
     * Builds the OAuth configuration of an account.
     *
     * @param setup the account setup
     * @return the configuration
     */
    public static OAuthConfig forAccount(AccountSetup setup) {
        String clientId = setup.setting("oauth.clientId", "");
        if (clientId.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "No Google OAuth client id is configured. Set oauth.clientId in the account "
                            + "settings or register a client as described in PROVIDER_SUPPORT.md.",
                    false);
        }
        String redirectUri = setup.setting("oauth.redirectUri", "");
        if (redirectUri.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "No redirect URI is configured for the Google sign in. Set oauth.redirectUri.",
                    false);
        }
        List<String> scopes = DEFAULT_SCOPES;
        String configured = setup.setting("oauth.scopes", "");
        if (!configured.isBlank()) {
            scopes = List.of(configured.split("[,\\s]+"));
        }
        return OAuthConfig.builder("gmail")
                .authorizationEndpoint(AUTHORIZATION_ENDPOINT)
                .tokenEndpoint(TOKEN_ENDPOINT)
                .revokeEndpoint(REVOKE_ENDPOINT)
                .clientId(clientId)
                .clientSecret(setup.setting("oauth.clientSecret", ""))
                .redirectUri(redirectUri)
                .scopes(scopes)
                .pkce(true)
                .build();
    }
}
