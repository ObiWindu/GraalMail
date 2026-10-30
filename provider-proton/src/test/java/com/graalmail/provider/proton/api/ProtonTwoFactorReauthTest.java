/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.proton.ProtonProvider;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stored-session 2FA: a missing code must ask for a code (not a remove/re-add),
 * and a supplied code must complete the login and return fresh tokens.
 */
class ProtonTwoFactorReauthTest {

    private static final byte[] CLIENT_EPHEMERAL = new byte[]{9, 9, 9};
    private static final byte[] CLIENT_PROOF = new byte[]{7, 7, 7};
    private static final byte[] SERVER_PROOF = new byte[]{8, 8, 8};

    private static ProtonSrp cannedSrp() {
        return new ProtonSrp((srp, password, version, salt, ephemeral, modulus) ->
                new ProtonSrp.Proofs(CLIENT_EPHEMERAL, CLIENT_PROOF, SERVER_PROOF));
    }

    private static String infoResponse() {
        return "{\"Code\":1000,\"Version\":4,\"Modulus\":\"m\",\"ServerEphemeral\":\""
                + Base64.getEncoder().encodeToString(new byte[]{1}) + "\",\"Salt\":\""
                + Base64.getEncoder().encodeToString(new byte[]{2}) + "\",\"SRPSession\":\"sess-1\"}";
    }

    private static String authResponse() {
        return "{\"Code\":1000,\"UID\":\"uid-2\",\"AccessToken\":\"access-2\","
                + "\"RefreshToken\":\"refresh-2\",\"ExpiresIn\":3600,\"Scope\":\"full\","
                + "\"PasswordMode\":1,\"ServerProof\":\""
                + Base64.getEncoder().encodeToString(new byte[]{8, 8, 8}) + "\"}";
    }

    private static AccountSetup setupWithoutTokens(String baseUrl) {
        Credential credential = new Credential("user@proton.me", "login-pass",
                TokenSet.empty(), "mailbox-pass");
        return AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@proton.me")
                .credential(credential)
                .settings(Map.of(
                        com.graalmail.provider.proton.ProtonProvider.SETTING_API_BASE_URL, baseUrl,
                        com.graalmail.provider.proton.ProtonProvider.SETTING_UID, "uid-1"))
                .build();
    }

    @Test
    void missingCodeAsksForCodeInsteadOfRemoveReadd() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, infoResponse());
            stub.respond(422, "{\"Code\":422,\"Error\":\"Two-factor authentication is enabled (scope password)\"}");
            String baseUrl = "http://127.0.0.1:" + stub.port();
            ProtonProvider provider = new ProtonProvider("linux",
                    transport -> new ProtonAuth(transport, cannedSrp()));
            ProviderException e = assertThrows(ProviderException.class,
                    () -> provider.openSession(setupWithoutTokens(baseUrl)));
            assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
            assertTrue(e.userMessage().toLowerCase().contains("two-factor code"),
                    "message was: " + e.userMessage());
            assertFalse(e.userMessage().toLowerCase().contains("remove the account"),
                    "must not tell the user to remove/re-add: " + e.userMessage());
        }
    }

    @Test
    void suppliedCodeCompletesLoginAndReturnsFreshSetup() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, infoResponse());
            stub.respond(422, "{\"Code\":422,\"Error\":\"scope\"}");
            stub.respond(200, authResponse());
            String baseUrl = "http://127.0.0.1:" + stub.port();
            ProtonProvider provider = new ProtonProvider("linux",
                    transport -> new ProtonAuth(transport, cannedSrp()));
            AccountSetup fresh = provider.reauthenticateWithTwoFactor(
                    setupWithoutTokens(baseUrl).withTwoFactorCode("123456"));
            assertEquals("access-2", fresh.credential().tokens().accessToken());
            assertEquals("refresh-2", fresh.credential().tokens().refreshToken());
            assertEquals("uid-2", fresh.setting(
                    com.graalmail.provider.proton.ProtonProvider.SETTING_UID, ""));
            // Passwords survive the refresh; 2FA code itself is not stored.
            assertEquals("login-pass", fresh.credential().secret());
            assertEquals("mailbox-pass", fresh.credential().keySecret());
            assertEquals("", fresh.twoFactorCode());
            StubProtonHttp.Request factor = stub.received().get(2);
            assertEquals("/auth/2fa", factor.path());
            assertTrue(factor.body().contains("123456"));
        }
    }

    @Test
    void reauthenticateWithoutCodeFailsFast() {
        ProtonProvider provider = new ProtonProvider();
        Credential credential = new Credential("user@proton.me", "login-pass",
                TokenSet.empty(), "");
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@proton.me")
                .credential(credential)
                .build();
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.reauthenticateWithTwoFactor(setup));
        assertEquals(ErrorCode.CONSENT_REQUIRED, e.code());
    }
}
