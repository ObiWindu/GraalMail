/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.graalmail.core.domain.Account;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.security.model.Credential;
import com.graalmail.security.store.CredentialStore;

/** Builds provider sessions from stored accounts and stored credentials. */
public class AccountSessionFactory {

    private final CredentialStore credentialStore;

    public AccountSessionFactory(CredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    public AccountProvider provider(Account account) {
        ProviderType type = ProviderType.fromId(account.providerId());
        return ProviderRegistry.find(type)
                .orElseThrow(() -> new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                        "No provider implementation for " + type.displayName(), false));
    }

    /** Opens a session for the account using the stored credential. */
    public ProviderSession open(Account account) {
        AccountProvider provider = provider(account);
        AccountSetup setup = setup(account, provider);
        return provider.openSession(setup);
    }

    /** Builds the setup without opening a session. */
    public AccountSetup setup(Account account, AccountProvider provider) {
        Credential credential = loadCredential(account);
        AccountSetup.Builder builder = AccountSetup.builder(authMethod(provider, credential))
                .email(account.email())
                .displayName(account.displayName())
                .settings(account.settings())
                .credential(credential);
        return builder.build();
    }

    /** Stores the credential for an account in the platform credential store. */
    public void saveCredential(Account account, Credential credential) {
        credentialStore.write(account.credentialRef() == null || account.credentialRef().isBlank()
                ? account.id() : account.credentialRef(), CredentialCodec.KEY,
                CredentialCodec.encode(credential));
    }

    public void deleteCredential(Account account) {
        credentialStore.delete(account.credentialRef() == null || account.credentialRef().isBlank()
                ? account.id() : account.credentialRef());
    }

    private Credential loadCredential(Account account) {
        String key = account.credentialRef() == null || account.credentialRef().isBlank()
                ? account.id() : account.credentialRef();
        String raw = credentialStore.read(key, CredentialCodec.KEY);
        if (raw == null || raw.isBlank()) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "No credentials stored for " + account.email() + ", please sign in again", false);
        }
        return CredentialCodec.decode(raw);
    }

    private static AuthMethod authMethod(AccountProvider provider, Credential credential) {
        if (credential.tokens().hasRefreshToken() || !credential.tokens().accessToken().isBlank()) {
            return AuthMethod.OAUTH2;
        }
        if (provider.capabilities().supportsAuthMethod(AuthMethod.APP_PASSWORD)
                && provider.capabilities().authMethods().size() == 1) {
            return AuthMethod.APP_PASSWORD;
        }
        return AuthMethod.PASSWORD;
    }
}
