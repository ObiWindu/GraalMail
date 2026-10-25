/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A configured mail account. Secrets are never stored here; see the credential store. */
public final class Account {
    private final String id;
    private final String providerId;
    private final String email;
    private final String displayName;
    private final Map<String, String> settings;
    private final String credentialRef;
    private final Instant createdAt;
    private final boolean enabled;

    private Account(Builder b) {
        this.id = Objects.requireNonNull(b.id, "id");
        this.providerId = Objects.requireNonNull(b.providerId, "providerId");
        this.email = b.email == null ? "" : b.email;
        this.displayName = b.displayName == null ? "" : b.displayName;
        this.settings = new LinkedHashMap<>(b.settings);
        this.credentialRef = b.credentialRef == null ? "" : b.credentialRef;
        this.createdAt = b.createdAt == null ? Instant.now() : b.createdAt;
        this.enabled = b.enabled;
    }

    public static Builder builder(String id, String providerId) {
        return new Builder(id, providerId);
    }

    public String id() { return id; }
    public String providerId() { return providerId; }
    public String email() { return email; }
    public String displayName() { return displayName; }
    public Map<String, String> settings() { return Map.copyOf(settings); }
    public String setting(String key) { return settings.get(key); }
    public String setting(String key, String fallback) {
        return settings.getOrDefault(key, fallback);
    }
    public String credentialRef() { return credentialRef; }
    public Instant createdAt() { return createdAt; }
    public boolean enabled() { return enabled; }

    public static final class Builder {
        private final String id;
        private final String providerId;
        private String email = "";
        private String displayName = "";
        private Map<String, String> settings = new LinkedHashMap<>();
        private String credentialRef = "";
        private Instant createdAt;
        private boolean enabled = true;

        private Builder(String id, String providerId) {
            this.id = id;
            this.providerId = providerId;
        }

        public Builder email(String v) { this.email = v; return this; }
        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder settings(Map<String, String> v) { this.settings = new LinkedHashMap<>(v); return this; }
        public Builder setting(String k, String v) { this.settings.put(k, v); return this; }
        public Builder credentialRef(String v) { this.credentialRef = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }

        public Account build() {
            return new Account(this);
        }
    }
}
