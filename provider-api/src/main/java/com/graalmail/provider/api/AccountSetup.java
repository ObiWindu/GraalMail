/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.security.model.Credential;
import com.graalmail.security.model.TokenSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Everything a provider needs to establish an account session. */
public final class AccountSetup {

    private final String email;
    private final String displayName;
    private final AuthMethod authMethod;
    private final Credential credential;
    private final Map<String, String> settings;

    private AccountSetup(Builder b) {
        this.email = b.email == null ? "" : b.email;
        this.displayName = b.displayName == null ? "" : b.displayName;
        this.authMethod = b.authMethod;
        this.credential = b.credential;
        this.settings = new LinkedHashMap<>(b.settings);
    }

    public static Builder builder(AuthMethod authMethod) {
        return new Builder(authMethod);
    }

    public String email() { return email; }
    public String displayName() { return displayName; }
    public AuthMethod authMethod() { return authMethod; }
    public Credential credential() { return credential; }
    public Map<String, String> settings() { return Map.copyOf(settings); }

    public String setting(String key) { return settings.get(key); }

    public String setting(String key, String fallback) {
        return settings.getOrDefault(key, fallback);
    }

    public boolean hasSettings() { return !settings.isEmpty(); }

    public static final class Builder {
        private final AuthMethod authMethod;
        private String email = "";
        private String displayName = "";
        private Credential credential;
        private final Map<String, String> settings = new LinkedHashMap<>();

        private Builder(AuthMethod authMethod) {
            this.authMethod = authMethod;
        }

        public Builder email(String v) { this.email = v; return this; }
        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder credential(Credential v) { this.credential = v; return this; }
        public Builder password(String username, String secret) {
            this.credential = Credential.password(username, secret);
            return this;
        }

        public Builder tokens(TokenSet tokens) {
            this.credential = Credential.oauth(email, tokens);
            return this;
        }

        public Builder setting(String key, String value) {
            settings.put(key, value);
            return this;
        }

        public Builder settings(Map<String, String> values) {
            settings.putAll(values);
            return this;
        }

        public AccountSetup build() {
            return new AccountSetup(this);
        }
    }
}
