/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.security.model.Credential;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountSetupTwoFactorTest {

    @Test
    void twoFactorCodeDefaultsToEmpty() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@proton.me")
                .password("user@proton.me", "secret")
                .build();
        assertEquals("", setup.twoFactorCode());
    }

    @Test
    void builderCarriesTwoFactorCode() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@proton.me")
                .password("user@proton.me", "secret")
                .twoFactorCode("123456")
                .build();
        assertEquals("123456", setup.twoFactorCode());
    }

    @Test
    void withTwoFactorCodeCopiesEverything() {
        Credential credential = Credential.password("user@proton.me", "secret");
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@proton.me")
                .displayName("User")
                .credential(credential)
                .setting("proton.uid", "uid-1")
                .build()
                .withTwoFactorCode("654321");
        assertEquals("654321", setup.twoFactorCode());
        assertEquals("user@proton.me", setup.email());
        assertEquals("User", setup.displayName());
        assertSame(credential, setup.credential());
        assertEquals("uid-1", setup.setting("proton.uid", ""));
        // Original is untouched.
        assertEquals("", AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@proton.me")
                .password("user@proton.me", "secret")
                .build().twoFactorCode());
    }

    @Test
    void nullCodeBecomesEmpty() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("u")
                .password("u", "s")
                .twoFactorCode(null)
                .build();
        assertEquals("", setup.twoFactorCode());
    }
}
