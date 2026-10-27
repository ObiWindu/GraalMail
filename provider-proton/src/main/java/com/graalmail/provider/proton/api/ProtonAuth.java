/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SRP authentication against the Proton API: {@code /auth/info},
 * {@code /auth}, {@code /auth/2fa} and {@code /auth/refresh}.
 */
public final class ProtonAuth {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProtonHttpTransport transport;
    private final ProtonSrp srp;

    public ProtonAuth(ProtonHttpTransport transport) {
        this(transport, new ProtonSrp());
    }

    ProtonAuth(ProtonHttpTransport transport, ProtonSrp srp) {
        this.transport = transport;
        this.srp = srp;
    }

    /** Access and refresh material from a successful login or refresh. */
    public record Tokens(String uid, String accessToken, String refreshToken,
                         Instant expiresAt, String scope, int passwordMode) {
    }

    /** Thrown when the account requires a two-factor code. */
    public static final class TwoFactorRequired extends RuntimeException {
        TwoFactorRequired() {
            super("Two-factor authentication is enabled for this Proton account.");
        }
    }

    /**
     * Logs in with the Proton username and login password.
     *
     * @throws TwoFactorRequired when a {@code twoFactorCode} must be supplied
     */
    public Tokens login(String username, String password) {
        return login(username, password, null);
    }

    public Tokens login(String username, String password, String twoFactorCode) {
        if (username == null || username.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton username.", false);
        }
        if (password == null || password.isEmpty()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Enter the Proton password.", false);
        }
        AuthInfo info = authInfo(username);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        ProtonSrp.Proofs proofs;
        try {
            byte[] salt = Base64.getDecoder().decode(info.salt());
            byte[] serverEphemeral = Base64.getDecoder().decode(info.serverEphemeral());
            proofs = srp.proofs(passwordBytes, info.version(), salt, serverEphemeral, info.modulus());
        } catch (ProviderException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "Proton answered with malformed SRP parameters.", false, e);
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "Proton login failed: " + e.getMessage(), false, e);
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("Username", username);
        payload.put("SRPSession", info.srpSession());
        payload.put("ClientEphemeral", Base64.getEncoder().encodeToString(proofs.clientEphemeral()));
        payload.put("ClientProof", Base64.getEncoder().encodeToString(proofs.clientProof()));
        ProtonHttpResponse response = post("/auth", payload, "log in");
        if (ProtonApiException.isTwoFactorRequired(response.status(), response.body())) {
            if (twoFactorCode == null || twoFactorCode.isBlank()) {
                throw new TwoFactorRequired();
            }
            return twoFactor(response, twoFactorCode, proofs);
        }
        if (!response.ok()) {
            throw ProtonApiException.fromResponse("log in", response.status(), response.body());
        }
        verifyProof(proofs, text(response, "ServerProof"));
        return tokens(response);
    }

    private Tokens twoFactor(ProtonHttpResponse loginResponse, String code, ProtonSrp.Proofs proofs) {
        Map<String, String> payload = Map.of("TwoFactorCode", code);
        ProtonHttpResponse response = post("/auth/2fa", payload, "confirm the two-factor code");
        if (!response.ok()) {
            throw ProtonApiException.fromResponse(
                    "confirm the two-factor code", response.status(), response.body());
        }
        // The server proof rides on the final response; fall back to the login
        // challenge when it carries one instead.
        String serverProof = text(response, "ServerProof");
        if (serverProof.isBlank()) {
            serverProof = text(loginResponse, "ServerProof");
        }
        if (!serverProof.isBlank()) {
            verifyProof(proofs, serverProof);
        }
        return tokens(response);
    }

    private static void verifyProof(ProtonSrp.Proofs proofs, String serverProof) {
        try {
            ProtonSrp.verifyServerProof(proofs.expectedServerProof(), serverProof);
        } catch (IllegalStateException e) {
            throw new ProviderException(ErrorCode.TLS_ERROR,
                    "Proton failed to prove its identity. The connection may be intercepted.", false, e);
        }
    }

    /** Refreshes an expired access token. */
    public Tokens refresh(String uid, String refreshToken) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("RefreshToken", refreshToken);
        payload.put("ResponseType", "token");
        payload.put("GrantType", "refresh_token");
        payload.put("RedirectURI", "https://proton.me");
        ProtonHttpResponse response = post("/auth/refresh", payload, Map.of("X-Pm-Uid", uid),
                "refresh the Proton session");
        if (!response.ok()) {
            throw ProtonApiException.fromResponse(
                    "refresh the Proton session", response.status(), response.body());
        }
        return tokens(response);
    }

    private record AuthInfo(int version, String modulus, String serverEphemeral,
                            String salt, String srpSession) {
    }

    private AuthInfo authInfo(String username) {
        ProtonHttpResponse response = post("/auth/info", Map.of("Username", username),
                "start the Proton login");
        if (!response.ok()) {
            throw ProtonApiException.fromResponse(
                    "start the Proton login", response.status(), response.body());
        }
        JsonNode root = json(response);
        return new AuthInfo(
                root.path("Version").asInt(-1),
                root.path("Modulus").asText(""),
                root.path("ServerEphemeral").asText(""),
                root.path("Salt").asText(""),
                root.path("SRPSession").asText(""));
    }

    private static Tokens tokens(ProtonHttpResponse response) {
        JsonNode root = json(response);
        int expiresIn = root.path("ExpiresIn").asInt(0);
        return new Tokens(
                root.path("UID").asText(""),
                root.path("AccessToken").asText(""),
                root.path("RefreshToken").asText(""),
                expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null,
                root.path("Scope").asText(""),
                root.path("PasswordMode").asInt(1));
    }

    private ProtonHttpResponse post(String path, Map<String, String> payload, String action) {
        return post(path, payload, Map.of(), action);
    }

    private ProtonHttpResponse post(String path, Map<String, String> payload,
                                    Map<String, String> headers, String action) {
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode Proton request", e);
        }
        try {
            return transport.execute(ProtonHttpRequest.post(path, body).withHeaders(headers));
        } catch (IOException e) {
            throw ProtonApiException.network(action, e);
        }
    }

    private static JsonNode json(ProtonHttpResponse response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "Proton answered with an unreadable response.", false, e);
        }
    }

    private static String text(ProtonHttpResponse response, String field) {
        JsonNode value = json(response).path(field);
        return value.isTextual() ? value.asText() : "";
    }
}
