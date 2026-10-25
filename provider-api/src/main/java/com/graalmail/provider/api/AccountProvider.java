/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ProviderException;

/** Entry point for a mail provider implementation. */
public interface AccountProvider {

    ProviderType type();

    String displayName();

    ProviderCapabilities capabilities();

    /**
     * Opens a session for the given account. Implementations must not cache
     * credentials in a way that leaks them into logs.
     */
    ProviderSession openSession(AccountSetup setup);

    /** Opens a session for a previously configured account. */
    default ProviderSession openSession(Account account, AccountSetup setup) {
        return openSession(setup);
    }

    /** Verifies that the configuration and credentials work. */
    default ConnectionTestResult testConnection(AccountSetup setup) {
        try (ProviderSession session = openSession(setup)) {
            return ConnectionTestResult.success(session.mailboxes().listFolders().size() + " folders");
        } catch (ProviderException e) {
            return ConnectionTestResult.failure(e.userMessage(), e.code().name());
        }
    }

    /** Revokes remote tokens when the provider supports it. */
    default void revoke(AccountSetup setup) {
        // no-op by default
    }
}
