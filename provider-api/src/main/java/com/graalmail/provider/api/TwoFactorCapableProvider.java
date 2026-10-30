/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

/**
 * Providers with an interactive second factor (TOTP) that can expire
 * independently of the stored password.
 *
 * <p>The one-time code travels only in {@link AccountSetup#twoFactorCode()}
 * and is never persisted. Gmail/Yahoo/Microsoft need no implementation here:
 * their second factor lives outside GraalMail (app passwords generated after
 * 2-Step Verification, or the OAuth browser flow).</p>
 */
public interface TwoFactorCapableProvider {

    /**
     * Re-logs in with the stored password plus the one-time code in
     * {@code setup.twoFactorCode()} and returns a fresh setup with new
     * tokens (and any provider settings such as a session UID).
     *
     * @throws com.graalmail.core.error.ProviderException when the code is
     *         missing, wrong, or the password no longer works
     */
    AccountSetup reauthenticateWithTwoFactor(AccountSetup setup);
}
