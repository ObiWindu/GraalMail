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
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.TwoFactorCapableProvider;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import com.graalmail.security.store.InMemoryCredentialStore;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountSessionFactoryTwoFactorTest {

    private static final class FakeTfaProvider implements AccountProvider, TwoFactorCapableProvider {
        String seenCode = null;

        @Override
        public ProviderType type() { return ProviderType.PROTON; }

        @Override
        public String displayName() { return "Fake Proton"; }

        @Override
        public ProviderCapabilities capabilities() {
            return ProviderCapabilities.builder(ProviderType.PROTON)
                    .authMethods(AuthMethod.PASSWORD)
                    .build();
        }

        @Override
        public ProviderSession openSession(AccountSetup setup) {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public AccountSetup reauthenticateWithTwoFactor(AccountSetup setup) {
            seenCode = setup.twoFactorCode();
            Credential fresh = new Credential(setup.email(), "pw",
                    new TokenSet("a2", "r2", Instant.now().plusSeconds(3600), "full", "", Map.of()),
                    "mailbox");
            return AccountSetup.builder(setup.authMethod())
                    .email(setup.email())
                    .displayName(setup.displayName())
                    .credential(fresh)
                    .settings(Map.of("proton.uid", "uid-2"))
                    .build();
        }
    }

    @Test
    void setupCarriesTwoFactorCodeWithoutPersistingIt() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(store);
        Account account = Account.builder("acc-1", "proton")
                .email("user@proton.me")
                .credentialRef("acc-1")
                .build();
        store.write("acc-1", CredentialCodec.KEY,
                CredentialCodec.encode(Credential.password("user@proton.me", "pw")));
        FakeTfaProvider provider = new FakeTfaProvider();

        AccountSetup setup = factory.setup(account, provider, "123456");
        assertEquals("123456", setup.twoFactorCode());
        // Codec round-trip carries no 2FA code: it is transient.
        Credential reloaded = CredentialCodec.decode(store.read("acc-1", CredentialCodec.KEY));
        assertEquals("pw", reloaded.secret());
    }

    @Test
    void reauthenticatePersistsFreshCredential() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(store) {
            @Override
            public AccountProvider provider(Account account) {
                return new FakeTfaProvider();
            }
        };
        Account account = Account.builder("acc-1", "proton")
                .email("user@proton.me")
                .credentialRef("acc-1")
                .build();
        store.write("acc-1", CredentialCodec.KEY,
                CredentialCodec.encode(Credential.password("user@proton.me", "pw")));

        AccountSetup fresh = factory.reauthenticateWithTwoFactor(account, "654321");
        assertEquals("uid-2", fresh.setting("proton.uid", ""));
        Credential saved = CredentialCodec.decode(store.read("acc-1", CredentialCodec.KEY));
        assertEquals("a2", saved.tokens().accessToken());
        assertEquals("r2", saved.tokens().refreshToken());
    }

    @Test
    void reauthenticateWithoutCodeFailsFast() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(store) {
            @Override
            public AccountProvider provider(Account account) {
                return new FakeTfaProvider();
            }
        };
        Account account = Account.builder("acc-1", "proton")
                .email("user@proton.me")
                .credentialRef("acc-1")
                .build();
        store.write("acc-1", CredentialCodec.KEY,
                CredentialCodec.encode(Credential.password("user@proton.me", "pw")));
        ProviderException e = assertThrows(ProviderException.class,
                () -> factory.reauthenticateWithTwoFactor(account, "  "));
        assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
    }

    @Test
    void reauthenticateOnProviderWithout2faExplainsAppPasswords() {
        InMemoryCredentialStore store = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(store) {
            @Override
            public AccountProvider provider(Account account) {
                return new AccountProvider() {
                    @Override
                    public ProviderType type() { return ProviderType.GMAIL; }

                    @Override
                    public String displayName() { return "Gmail"; }

                    @Override
                    public ProviderCapabilities capabilities() {
                        return ProviderCapabilities.builder(ProviderType.GMAIL)
                                .authMethods(AuthMethod.APP_PASSWORD)
                                .build();
                    }

                    @Override
                    public ProviderSession openSession(AccountSetup setup) {
                        throw new UnsupportedOperationException("not needed");
                    }
                };
            }
        };
        Account account = Account.builder("acc-1", "gmail")
                .email("user@gmail.com")
                .credentialRef("acc-1")
                .build();
        store.write("acc-1", CredentialCodec.KEY,
                CredentialCodec.encode(Credential.password("user@gmail.com", "pw")));
        ProviderException e = assertThrows(ProviderException.class,
                () -> factory.reauthenticateWithTwoFactor(account, "123456"));
        assertEquals(ErrorCode.FEATURE_UNSUPPORTED, e.code());
    }
}
