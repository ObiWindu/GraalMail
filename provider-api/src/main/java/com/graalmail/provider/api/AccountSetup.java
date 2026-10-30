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
    private final String twoFactorCode;

    private AccountSetup(Builder b) {
        this.email = b.email == null ? "" : b.email;
        this.displayName = b.displayName == null ? "" : b.displayName;
        this.authMethod = b.authMethod;
        this.credential = b.credential;
        this.settings = new LinkedHashMap<>(b.settings);
        this.twoFactorCode = b.twoFactorCode == null ? "" : b.twoFactorCode;
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

    /** One-time second-factor code (TOTP). Never persisted, never logged. */
    public String twoFactorCode() { return twoFactorCode; }

    /** Copies this setup with a one-time second-factor code. */
    public AccountSetup withTwoFactorCode(String code) {
        return builder(authMethod)
                .email(email)
                .displayName(displayName)
                .credential(credential)
                .settings(settings)
                .twoFactorCode(code == null ? "" : code)
                .build();
    }

    public static final class Builder {
        private final AuthMethod authMethod;
        private String email = "";
        private String displayName = "";
        private Credential credential;
        private final Map<String, String> settings = new LinkedHashMap<>();
        private String twoFactorCode = "";

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

        /** One-time second-factor code. Never persisted. */
        public Builder twoFactorCode(String v) {
            this.twoFactorCode = v == null ? "" : v;
            return this;
        }

        public AccountSetup build() {
            return new AccountSetup(this);
        }
    }
}
