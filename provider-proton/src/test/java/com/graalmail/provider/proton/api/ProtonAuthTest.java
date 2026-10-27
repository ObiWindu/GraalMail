/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auth flow tests with canned SRP proofs and a stub HTTP server.
 *
 * <p>The SRP math itself is covered by {@link ProtonSrpTest}; here the proof
 * computation is stubbed so the request/response mapping can be verified.</p>
 */
class ProtonAuthTest {

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
        return "{\"Code\":1000,\"UID\":\"uid-1\",\"AccessToken\":\"access-1\","
                + "\"RefreshToken\":\"refresh-1\",\"ExpiresIn\":3600,\"Scope\":\"full\","
                + "\"PasswordMode\":1,\"ServerProof\":\""
                + Base64.getEncoder().encodeToString(SERVER_PROOF) + "\"}";
    }

    @Test
    void loginSucceeds() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, infoResponse());
            stub.respond(200, authResponse());
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "test-app@1", 2_000, 2_000);
            ProtonAuth.Tokens tokens = new ProtonAuth(transport, cannedSrp()).login("user", "pass");
            assertEquals("uid-1", tokens.uid());
            assertEquals("access-1", tokens.accessToken());
            assertEquals("refresh-1", tokens.refreshToken());
            assertEquals(1, tokens.passwordMode());
            assertNotNull(tokens.expiresAt());
            StubProtonHttp.Request auth = stub.received().get(1);
            assertEquals("/auth", auth.path());
            assertTrue(auth.body().contains("sess-1"));
            assertTrue(auth.body().contains(
                    Base64.getEncoder().encodeToString(CLIENT_EPHEMERAL)));
        }
    }

    @Test
    void twoFactorFlow() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, infoResponse());
            stub.respond(422, "{\"Code\":422,\"Error\":\"Two-factor authentication is enabled (scope password)\"}");
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "test-app@1", 2_000, 2_000);
            ProtonAuth auth = new ProtonAuth(transport, cannedSrp());
            assertThrows(ProtonAuth.TwoFactorRequired.class, () -> auth.login("user", "pass"));

            try (StubProtonHttp second = new StubProtonHttp()) {
                second.respond(200, infoResponse());
                second.respond(422, "{\"Code\":422,\"Error\":\"scope\"}");
                second.respond(200, authResponse());
                ProtonHttpTransport retry = new DefaultProtonHttpTransport(
                        "http://127.0.0.1:" + second.port(), "test-app@1", 2_000, 2_000);
                ProtonAuth.Tokens tokens = new ProtonAuth(retry, cannedSrp())
                        .login("user", "pass", "123456");
                assertEquals("access-1", tokens.accessToken());
                StubProtonHttp.Request factor = second.received().get(2);
                assertEquals("/auth/2fa", factor.path());
                assertTrue(factor.body().contains("123456"));
            }
        }
    }

    @Test
    void wrongPasswordMapsToAuthenticationFailed() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, infoResponse());
            stub.respond(401, "{\"Code\":401,\"Error\":\"Invalid credentials\"}");
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "test-app@1", 2_000, 2_000);
            ProviderException e = assertThrows(ProviderException.class,
                    () -> new ProtonAuth(transport, cannedSrp()).login("user", "wrong"));
            assertEquals(ErrorCode.AUTHENTICATION_FAILED, e.code());
        }
    }

    @Test
    void refreshReturnsNewTokens() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            stub.respond(200, "{\"Code\":1000,\"UID\":\"uid-1\",\"AccessToken\":\"access-2\","
                    + "\"RefreshToken\":\"refresh-2\",\"ExpiresIn\":7200,\"Scope\":\"full\",\"PasswordMode\":2}");
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "test-app@1", 2_000, 2_000);
            ProtonAuth.Tokens tokens = new ProtonAuth(transport, cannedSrp())
                    .refresh("uid-1", "refresh-1");
            assertEquals("access-2", tokens.accessToken());
            assertEquals(2, tokens.passwordMode());
            StubProtonHttp.Request refresh = stub.received().get(0);
            assertEquals("/auth/refresh", refresh.path());
            assertEquals("uid-1", refresh.headers().get("X-Pm-Uid"));
            assertTrue(refresh.body().contains("refresh-1"));
        }
    }

    @Test
    void blankCredentialsAreRejectedLocally() throws Exception {
        try (StubProtonHttp stub = new StubProtonHttp()) {
            ProtonHttpTransport transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "test-app@1", 2_000, 2_000);
            ProtonAuth auth = new ProtonAuth(transport, cannedSrp());
            assertThrows(ProviderException.class, () -> auth.login("  ", "pass"));
            assertThrows(ProviderException.class, () -> auth.login("user", ""));
            assertTrue(stub.received().isEmpty());
        }
    }
}
