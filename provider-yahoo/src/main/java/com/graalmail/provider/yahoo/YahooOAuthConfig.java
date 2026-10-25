/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.yahoo;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.security.oauth.OAuthConfig;
import java.util.List;

/**
 * Builds the {@link OAuthConfig} for Yahoo.
 *
 * <p>The mail scopes of Yahoo are not available for self service. A
 * distribution that wants the OAuth sign in has to register an application at
 * the Yahoo developer network and to apply for the mail scope.</p>
 */
public final class YahooOAuthConfig {

    /** Scope that allows reading the mailbox. */
    public static final String SCOPE_MAIL_READ = "mail-r";
    /** Scope that allows reading and writing the mailbox. */
    public static final String SCOPE_MAIL_READ_WRITE = "mail-rw";
    /** Scope that exposes the address of the signed in user. */
    public static final String SCOPE_OPENID = "openid";

    private static final List<String> DEFAULT_SCOPES =
            List.of(SCOPE_MAIL_READ_WRITE, SCOPE_OPENID);

    private YahooOAuthConfig() {
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
                    "No Yahoo OAuth client id is configured. Yahoo only grants the mail scopes after "
                            + "a manual review, so most installations use an app password instead.",
                    false);
        }
        String redirectUri = setup.setting("oauth.redirectUri", "");
        if (redirectUri.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "No redirect URI is configured for the Yahoo sign in. Set oauth.redirectUri.",
                    false);
        }
        List<String> scopes = DEFAULT_SCOPES;
        String configured = setup.setting("oauth.scopes", "");
        if (!configured.isBlank()) {
            scopes = List.of(configured.split("[,\\s]+"));
        }
        return OAuthConfig.builder("yahoo")
                .authorizationEndpoint(YahooProvider.AUTHORIZATION_ENDPOINT)
                .tokenEndpoint(YahooProvider.TOKEN_ENDPOINT)
                .revokeEndpoint(YahooProvider.REVOKE_ENDPOINT)
                .clientId(clientId)
                .clientSecret(setup.setting("oauth.clientSecret", ""))
                .redirectUri(redirectUri)
                .scopes(scopes)
                .pkce(true)
                .build();
    }
}
