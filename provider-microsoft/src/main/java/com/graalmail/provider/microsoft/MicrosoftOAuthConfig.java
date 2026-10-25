/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.security.oauth.OAuthConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link OAuthConfig} for the Microsoft identity platform.
 *
 * <p>The client id is never hard coded: it is read from the account setting
 * {@code oauth.clientId}. The redirect URI comes from {@code oauth.redirectUri}
 * and the tenant from {@code oauth.tenant} which defaults to {@code common}.</p>
 */
public final class MicrosoftOAuthConfig {

    /** Azure AD authority host. */
    public static final String AUTHORITY = "https://login.microsoftonline.com";

    /** Scope that makes the identity provider return a refresh token. */
    public static final String SCOPE_OFFLINE_ACCESS = "offline_access";
    /** OpenID Connect scope. */
    public static final String SCOPE_OPENID = "openid";
    /** Scope that exposes the primary mail address. */
    public static final String SCOPE_EMAIL = "email";
    /** Scope that exposes the profile. */
    public static final String SCOPE_PROFILE = "profile";
    /** Delegated permission that allows IMAP access as the signed in user. */
    public static final String SCOPE_IMAP_ACCESS = "https://outlook.office.com/IMAP.AccessAsUser.All";
    /** Delegated permission that allows sending mail as the signed in user. */
    public static final String SCOPE_SMTP_SEND = "https://outlook.office.com/SMTP.Send";
    /** Delegated permission that allows reading and writing mail through Graph. */
    public static final String SCOPE_GRAPH_MAIL_RW = "https://graph.microsoft.com/Mail.ReadWrite";

    /** Setting key for the registered Azure application client id. */
    public static final String SETTING_CLIENT_ID = "oauth.clientId";
    /** Setting key for the redirect URI of the registered application. */
    public static final String SETTING_REDIRECT_URI = "oauth.redirectUri";
    /** Setting key for the Azure tenant identifier. */
    public static final String SETTING_TENANT = "oauth.tenant";

    private MicrosoftOAuthConfig() {
    }

    /**
     * Builds the OAuth configuration for an account.
     *
     * @param setup the account setup carrying the OAuth settings
     * @param mode the transport the scopes have to cover
     * @return the OAuth configuration
     * @throws ProviderException with {@code CONFIGURATION_ERROR} when the client
     *         id or the redirect URI is missing
     */
    public static OAuthConfig build(AccountSetup setup, MicrosoftTransportMode mode) {
        return build(resolveTenant(setup), setup.setting(SETTING_CLIENT_ID),
                setup.setting(SETTING_REDIRECT_URI), mode);
    }

    /**
     * Builds the OAuth configuration from explicit values.
     *
     * @param tenant the tenant identifier, {@code common} when {@code null}
     * @param clientId the registered application client id
     * @param redirectUri the redirect URI of the registered application
     * @param mode the transport the scopes have to cover
     * @return the OAuth configuration
     * @throws ProviderException with {@code CONFIGURATION_ERROR} when the client
     *         id or the redirect URI is missing
     */
    public static OAuthConfig build(String tenant, String clientId, String redirectUri,
                                    MicrosoftTransportMode mode) {
        if (clientId == null || clientId.isBlank()) {
            throw ProviderException.configuration(
                    "A registered Azure application client id is required; set the account setting '"
                            + SETTING_CLIENT_ID + "'.");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw ProviderException.configuration(
                    "A redirect URI is required; set the account setting '"
                            + SETTING_REDIRECT_URI + "'.");
        }
        String authority = AUTHORITY + "/" + resolveTenantValue(tenant);
        return OAuthConfig.builder("microsoft")
                .authorizationEndpoint(authority + "/oauth2/v2.0/authorize")
                .tokenEndpoint(authority + "/oauth2/v2.0/token")
                .revokeEndpoint(authority + "/oauth2/v2.0/logout")
                .clientId(clientId.trim())
                .redirectUri(redirectUri.trim())
                .scopes(scopesFor(mode))
                .pkce(true)
                .build();
    }

    /**
     * The scopes requested for a transport.
     *
     * @param mode the transport
     * @return the scope list, always including {@code offline_access}
     */
    public static List<String> scopesFor(MicrosoftTransportMode mode) {
        List<String> scopes = new ArrayList<>();
        scopes.add(SCOPE_OFFLINE_ACCESS);
        scopes.add(SCOPE_OPENID);
        scopes.add(SCOPE_EMAIL);
        scopes.add(SCOPE_PROFILE);
        if (mode == MicrosoftTransportMode.GRAPH) {
            scopes.add(SCOPE_GRAPH_MAIL_RW);
        } else {
            scopes.add(SCOPE_IMAP_ACCESS);
            scopes.add(SCOPE_SMTP_SEND);
        }
        return scopes;
    }

    /**
     * Resolves the tenant identifier of an account setup.
     *
     * @param setup the account setup
     * @return the tenant identifier, {@code common} when not configured
     */
    public static String resolveTenant(AccountSetup setup) {
        return resolveTenantValue(setup == null ? null : setup.setting(SETTING_TENANT));
    }

    /**
     * Normalizes a tenant identifier.
     *
     * @param tenant the raw tenant identifier
     * @return the tenant identifier, {@code common} when {@code null} or blank
     */
    public static String resolveTenantValue(String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return MicrosoftAccountType.COMMON_TENANT;
        }
        return tenant.trim();
    }
}
