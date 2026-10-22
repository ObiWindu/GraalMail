/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.security.model.TokenSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal OAuth 2.0 client built on {@code HttpURLConnection} so it works on
 * both desktop JVM and Android. Supports the authorization code flow with PKCE,
 * refresh and revocation.
 */
public final class OAuthClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OAuthConfig config;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public OAuthClient(OAuthConfig config) {
        this(config, 15_000, 30_000);
    }

    public OAuthClient(OAuthConfig config, int connectTimeoutMillis, int readTimeoutMillis) {
        this.config = config;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    public OAuthConfig config() {
        return config;
    }

    public AuthorizationRequest beginAuthorization() {
        return AuthorizationRequest.create(config);
    }

    /** Exchanges an authorization code for tokens. */
    public TokenSet exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", config.redirectUri());
        form.put("client_id", config.clientId());
        if (config.hasClientSecret()) {
            form.put("client_secret", config.clientSecret());
        }
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form.put("code_verifier", codeVerifier);
        }
        JsonNode response = postForm(config.tokenEndpoint(), form, true);
        return toTokenSet(response);
    }

    /** Refreshes an access token using the refresh token. */
    public TokenSet refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ProviderException.authExpired("No refresh token available, re-authorization required");
        }
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", config.clientId());
        if (config.hasClientSecret()) {
            form.put("client_secret", config.clientSecret());
        }
        JsonNode response = postForm(config.tokenEndpoint(), form, true);
        TokenSet tokens = toTokenSet(response);
        if (!tokens.hasRefreshToken()) {
            return tokens.withRefreshToken(refreshToken, tokens.expiresAt());
        }
        return tokens;
    }

    /** Revokes a token when the provider supports it. Failures are not fatal. */
    public boolean revoke(String token) {
        if (config.revokeEndpoint() == null || config.revokeEndpoint().isBlank() || token == null
                || token.isBlank()) {
            return false;
        }
        try {
            Map<String, String> form = new HashMap<>();
            form.put("token", token);
            form.put("client_id", config.clientId());
            if (config.hasClientSecret()) {
                form.put("client_secret", config.clientSecret());
            }
            postForm(config.revokeEndpoint(), form, false);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TokenSet toTokenSet(JsonNode node) {
        if (node == null || !node.hasNonNull("access_token")) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "The provider did not return an access token", false);
        }
        String access = node.get("access_token").asText();
        String refresh = node.hasNonNull("refresh_token") ? node.get("refresh_token").asText() : "";
        String scope = node.hasNonNull("scope") ? node.get("scope").asText() : "";
        String idToken = node.hasNonNull("id_token") ? node.get("id_token").asText() : "";
        Instant expiry = null;
        if (node.hasNonNull("expires_in")) {
            long seconds = node.get("expires_in").asLong(3600);
            expiry = Instant.now().plusSeconds(seconds);
        }
        Map<String, String> extras = new HashMap<>();
        node.fields().forEachRemaining(entry -> extras.put(entry.getKey(), entry.getValue().asText("")));
        return new TokenSet(access, refresh, expiry, scope, idToken, extras);
    }

    private JsonNode postForm(String endpoint, Map<String, String> form, boolean expectJson) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(endpoint).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMillis);
            conn.setReadTimeout(readTimeoutMillis);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = encodeForm(form).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            String body = readBody(conn, status);
            JsonNode node = body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
            if (status >= 400) {
                throw errorFor(status, node);
            }
            return expectJson ? node : node;
        } catch (ProviderException e) {
            throw e;
        } catch (IOException e) {
            throw ProviderException.network("Could not reach the authorization server: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "Unexpected response from the authorization server", false, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private ProviderException errorFor(int status, JsonNode node) {
        String description = node.hasNonNull("error_description") ? node.get("error_description").asText()
                : node.hasNonNull("error") ? node.get("error").asText() : "HTTP " + status;
        if (status == 400 && description.toLowerCase().contains("invalid_grant")) {
            return ProviderException.authExpired("Authorization expired or was revoked, please sign in again");
        }
        if (status == 401 || status == 403) {
            return new ProviderException(ErrorCode.PERMISSION_DENIED,
                    "Access denied: " + description, false);
        }
        if (status >= 500) {
            return new ProviderException(ErrorCode.SERVER_ERROR,
                    "The authorization server is temporarily unavailable: " + description, true);
        }
        return new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                "Authorization failed: " + description, false);
    }

    private static String readBody(HttpURLConnection conn, int status) {
        try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String encodeForm(Map<String, String> form) {
        List<String> parts = new ArrayList<>();
        form.forEach((k, v) -> parts.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
        return String.join("&", parts);
    }
}
