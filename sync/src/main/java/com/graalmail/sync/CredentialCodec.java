/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serialises credentials for the platform credential store. */
public final class CredentialCodec {

    public static final String KEY = "credential";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CredentialCodec() {
    }

    public static String encode(Credential credential) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", credential.username());
        map.put("secret", credential.secret());
        TokenSet tokens = credential.tokens();
        map.put("accessToken", tokens.accessToken());
        map.put("refreshToken", tokens.refreshToken());
        map.put("scope", tokens.scope());
        map.put("idToken", tokens.idToken());
        map.put("expiresAt", tokens.expiresAt() == null ? 0L : tokens.expiresAt().toEpochMilli());
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode credential", e);
        }
    }

    public static Credential decode(String json) {
        try {
            Map<String, String> map = MAPPER.readValue(json, new TypeRef());
            TokenSet tokens = new TokenSet(map.get("accessToken"), map.get("refreshToken"),
                    map.get("expiresAt") == null || map.get("expiresAt").isBlank()
                            ? null : Instant.ofEpochMilli(Long.parseLong(map.get("expiresAt"))),
                    map.get("scope"), map.get("idToken"), Map.of());
            return new Credential(map.get("username"), map.get("secret"), tokens);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decode credential", e);
        }
    }

    private static final class TypeRef extends com.fasterxml.jackson.core.type.TypeReference<Map<String, String>> {
    }
}
