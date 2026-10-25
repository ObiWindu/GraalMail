/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.security.oauth.AuthorizationRequest;

/** A provider that authenticates through an OAuth 2.0 authorization code flow. */
public interface OAuthCapableProvider {

    /** Creates the authorization request and the URL the user must visit. */
    AuthorizationRequest beginAuthorization(AccountSetup setup);

    /**
     * Completes the flow. The implementation validates the state parameter,
     * exchanges the code and returns the resulting setup including tokens.
     */
    AccountSetup completeAuthorization(AccountSetup setup, String code, String state,
                                       String receivedState, String codeVerifier);

    /** True when the provider requires a registered OAuth client id. */
    default boolean requiresClientRegistration() {
        return true;
    }
}
