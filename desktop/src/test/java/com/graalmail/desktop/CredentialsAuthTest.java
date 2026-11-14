/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Account;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.TwoFactorCapableProvider;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import com.graalmail.security.store.InMemoryCredentialStore;
import com.graalmail.sync.AccountSessionFactory;
import com.graalmail.sync.CredentialCodec;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every hosted provider of the distribution must be addable with nothing
 * but the login (email address) and a password — an account password or a
 * provider app password — and the provider that owns an interactive second
 * factor must implement {@link TwoFactorCapableProvider}.
 */
class CredentialsAuthTest {

    private static final Set<ProviderType> HOSTED = EnumSet.of(
            ProviderType.GMAIL, ProviderType.MICROSOFT, ProviderType.YAHOO, ProviderType.PROTON);

    @Test
    void hostedProvidersAcceptPasswordCredentials() {
        for (ProviderType type : HOSTED) {
            AccountProvider provider = ProviderRegistry.find(type).orElseThrow();
            boolean passwordFamily = provider.capabilities().supportsAuthMethod(AuthMethod.PASSWORD)
                    || provider.capabilities().supportsAuthMethod(AuthMethod.APP_PASSWORD);
            assertTrue(passwordFamily,
                    type + " must accept a login + password (or app password) sign-in");
        }
    }

    @Test
    void noHostedProviderRequiresOAuthForNewAccounts() {
        for (ProviderType type : HOSTED) {
            AccountProvider provider = ProviderRegistry.find(type).orElseThrow();
            // OAuth may remain for legacy accounts, but it must not be the only way in.
            assertTrue(provider.capabilities().authMethods().size() > 1
                            || !provider.capabilities().supportsAuthMethod(AuthMethod.OAUTH2),
                    type + " must not be OAuth-only");
        }
    }

    @Test
    void protonSupportsInteractiveTwoFactor() {
        AccountProvider proton = ProviderRegistry.find(ProviderType.PROTON).orElseThrow();
        assertTrue(proton instanceof TwoFactorCapableProvider,
                "Proton owns the TOTP second factor and must implement TwoFactorCapableProvider");
        assertTrue(proton.capabilities().supportsAuthMethod(AuthMethod.PASSWORD));
    }

    @Test
    void storedPasswordAccountsResolveToPasswordAuth() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(store);
        for (ProviderType type : HOSTED) {
            AccountProvider provider = ProviderRegistry.find(type).orElseThrow();
            Account account = Account.builder("acc-" + type.id(), type.id())
                    .email("user@" + type.id() + ".example")
                    .credentialRef("acc-" + type.id())
                    .build();
            store.write(account.credentialRef(), CredentialCodec.KEY,
                    CredentialCodec.encode(Credential.password(account.email(), "secret")));
            AccountSetup setup = factory.setup(account, provider);
            assertTrue(setup.authMethod() == AuthMethod.PASSWORD
                            || setup.authMethod() == AuthMethod.APP_PASSWORD,
                    type + " password account must resolve to a password auth method, got "
                            + setup.authMethod());
        }
    }

    @Test
    void legacyMicrosoftOAuthAccountStillResolvesToOAuth() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(store);
        AccountProvider microsoft = ProviderRegistry.find(ProviderType.MICROSOFT).orElseThrow();
        Account account = Account.builder("acc-ms", "microsoft")
                .email("user@outlook.com")
                .credentialRef("acc-ms")
                .build();
        TokenSet tokens = new TokenSet("access", "refresh",
                Instant.now().plusSeconds(3600), "scope", "", Map.of());
        store.write(account.credentialRef(), CredentialCodec.KEY,
                CredentialCodec.encode(new Credential(account.email(), "", tokens, "")));
        AccountSetup setup = factory.setup(account, microsoft);
        assertEquals(AuthMethod.OAUTH2, setup.authMethod(),
                "legacy Microsoft accounts with stored tokens must keep using OAuth");
    }
}
