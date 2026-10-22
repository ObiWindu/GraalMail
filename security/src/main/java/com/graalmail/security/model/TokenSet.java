/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** OAuth token material. Never logged in full. */
public final class TokenSet {
    private final String accessToken;
    private final String refreshToken;
    private final Instant expiresAt;
    private final String scope;
    private final String idToken;
    private final Map<String, String> extras;

    public TokenSet(String accessToken, String refreshToken, Instant expiresAt, String scope,
                    String idToken, Map<String, String> extras) {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.refreshToken = refreshToken == null ? "" : refreshToken;
        this.expiresAt = expiresAt;
        this.scope = scope == null ? "" : scope;
        this.idToken = idToken == null ? "" : idToken;
        this.extras = extras == null ? Map.of() : new LinkedHashMap<>(extras);
    }

    public static TokenSet empty() {
        return new TokenSet("", "", null, "", "", Map.of());
    }

    public String accessToken() { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public Instant expiresAt() { return expiresAt; }
    public String scope() { return scope; }
    public String idToken() { return idToken; }
    public Map<String, String> extras() { return Collections.unmodifiableMap(extras); }

    public boolean hasRefreshToken() {
        return !refreshToken.isBlank();
    }

    public boolean isExpired(Instant now, long leewaySeconds) {
        if (expiresAt == null) {
            return false;
        }
        return now.plusSeconds(leewaySeconds).isAfter(expiresAt);
    }

    public boolean isExpired(Instant now) {
        return isExpired(now, 60);
    }

    public TokenSet withAccessToken(String token, Instant newExpiry, String newScope) {
        return new TokenSet(token, refreshToken, newExpiry, newScope == null ? scope : newScope,
                idToken, extras);
    }

    public TokenSet withRefreshToken(String token, Instant newExpiry) {
        return new TokenSet(accessToken, token, newExpiry, scope, idToken, extras);
    }

    @Override
    public String toString() {
        return "TokenSet{expiresAt=" + expiresAt + ", scope='" + scope + "', hasRefresh="
                + hasRefreshToken() + "}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TokenSet t && Objects.equals(accessToken, t.accessToken)
                && Objects.equals(refreshToken, t.refreshToken) && Objects.equals(expiresAt, t.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessToken, refreshToken, expiresAt);
    }
}
