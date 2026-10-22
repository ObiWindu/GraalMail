/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.core.util.Log;
import com.graalmail.security.model.TokenSet;
import com.graalmail.security.oauth.OAuthClient;
import com.graalmail.security.oauth.OAuthConfig;
import java.time.Instant;
import org.slf4j.Logger;

/**
 * Holds the OAuth tokens of one Microsoft account and refreshes the access
 * token when it expires.
 *
 * <p>The refresh request is performed with {@link OAuthClient#refresh(String)}.
 * The {@code offline_access} scope is part of the granted scope set, therefore
 * the identity provider issues a refresh token that can be used repeatedly.</p>
 */
public final class MicrosoftTokenManager {

    private static final Logger LOG = Log.of(MicrosoftTokenManager.class);

    private final OAuthClient client;
    private TokenSet tokens;

    /**
     * Creates a token manager.
     *
     * @param config the OAuth configuration
     * @param tokens the current tokens
     */
    public MicrosoftTokenManager(OAuthConfig config, TokenSet tokens) {
        this.client = new OAuthClient(config);
        this.tokens = tokens == null ? TokenSet.empty() : tokens;
    }

    /**
     * Returns a valid access token, refreshing it when needed.
     *
     * @return the access token
     * @throws ProviderException with {@code AUTH_EXPIRED} when the refresh token
     *         was revoked, or {@code PERMISSION_DENIED} when the tenant blocks
     *         the requested permissions
     */
    public String accessToken() {
        if (tokens.isExpired(Instant.now(), 60) && tokens.hasRefreshToken()) {
            refresh();
        }
        String token = tokens.accessToken();
        if (token.isBlank()) {
            throw ProviderException.authExpired(
                    "No Microsoft access token available, please sign in again.");
        }
        return token;
    }

    /**
     * Refreshes the access token using the stored refresh token.
     *
     * @return the refreshed token set
     * @throws ProviderException when the refresh fails
     */
    public TokenSet refresh() {
        if (!tokens.hasRefreshToken()) {
            throw ProviderException.authExpired(
                    "No Microsoft refresh token available, please sign in again.");
        }
        try {
            TokenSet refreshed = client.refresh(tokens.refreshToken());
            String scope = refreshed.scope();
            if (!scope.contains(MicrosoftOAuthConfig.SCOPE_OFFLINE_ACCESS)
                    && tokens.scope().contains(MicrosoftOAuthConfig.SCOPE_OFFLINE_ACCESS)) {
                scope = tokens.scope();
            }
            tokens = new TokenSet(refreshed.accessToken(),
                    refreshed.hasRefreshToken() ? refreshed.refreshToken() : tokens.refreshToken(),
                    refreshed.expiresAt(), scope, refreshed.idToken(), refreshed.extras());
            LOG.debug("Refreshed Microsoft access token, scope '{}'", scope);
            return tokens;
        } catch (ProviderException e) {
            throw MicrosoftErrorMapper.classify(e.userMessage(), e.code());
        }
    }

    /**
     * The current tokens.
     *
     * @return the token set
     */
    public TokenSet tokens() {
        return tokens;
    }

    /**
     * Replaces the tokens, for example after a re-authorization.
     *
     * @param newTokens the new token set
     */
    public void replaceTokens(TokenSet newTokens) {
        this.tokens = newTokens == null ? TokenSet.empty() : newTokens;
    }

    /**
     * True when the account still holds a refresh token.
     *
     * @return true when a refresh token is present
     */
    public boolean canRefresh() {
        return tokens.hasRefreshToken();
    }

    /**
     * True when the tokens are expired and can be refreshed.
     *
     * @return true when a refresh is required
     */
    public boolean needsRefresh() {
        return tokens.isExpired(Instant.now(), 60) && canRefresh();
    }

    /**
     * The error code that a failed refresh maps to, for diagnostics.
     *
     * @return the error code of the last classification
     */
    public static ErrorCode expiredCode() {
        return ErrorCode.AUTH_EXPIRED;
    }
}
