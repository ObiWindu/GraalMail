/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

/**
 * Supplies a currently valid OAuth access token for XOAUTH2 authentication.
 *
 * <p>Implementations may refresh the token on demand. They must never log the
 * token value.</p>
 */
@FunctionalInterface
public interface AccessTokenSupplier {

    /**
     * Returns a usable access token.
     *
     * @return the bearer token
     * @throws com.graalmail.core.error.ProviderException when no valid token can be provided
     */
    String accessToken();
}
