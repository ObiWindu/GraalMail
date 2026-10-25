/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.oauth;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static configuration of an OAuth client. Client secrets are optional:
 * mobile and desktop public clients use PKCE and no secret.
 */
public final class OAuthConfig {

    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String revokeEndpoint;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final Set<String> scopes;
    private final boolean pkce;
    private final String providerId;

    private OAuthConfig(Builder b) {
        this.authorizationEndpoint = b.authorizationEndpoint;
        this.tokenEndpoint = b.tokenEndpoint;
        this.revokeEndpoint = b.revokeEndpoint;
        this.clientId = b.clientId;
        this.clientSecret = b.clientSecret;
        this.redirectUri = b.redirectUri;
        this.scopes = Set.copyOf(b.scopes);
        this.pkce = b.pkce;
        this.providerId = b.providerId;
    }

    public static Builder builder(String providerId) {
        return new Builder(providerId);
    }

    public String authorizationEndpoint() { return authorizationEndpoint; }
    public String tokenEndpoint() { return tokenEndpoint; }
    public String revokeEndpoint() { return revokeEndpoint; }
    public String clientId() { return clientId; }
    public String clientSecret() { return clientSecret; }
    public boolean hasClientSecret() { return clientSecret != null && !clientSecret.isBlank(); }
    public String redirectUri() { return redirectUri; }
    public Set<String> scopes() { return scopes; }
    public boolean usePkce() { return pkce; }
    public String providerId() { return providerId; }

    public static final class Builder {
        private final String providerId;
        private String authorizationEndpoint = "";
        private String tokenEndpoint = "";
        private String revokeEndpoint = "";
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";
        private Set<String> scopes = new LinkedHashSet<>();
        private boolean pkce = true;

        private Builder(String providerId) {
            this.providerId = providerId;
        }

        public Builder authorizationEndpoint(String v) { this.authorizationEndpoint = v; return this; }
        public Builder tokenEndpoint(String v) { this.tokenEndpoint = v; return this; }
        public Builder revokeEndpoint(String v) { this.revokeEndpoint = v; return this; }
        public Builder clientId(String v) { this.clientId = v; return this; }
        public Builder clientSecret(String v) { this.clientSecret = v; return this; }
        public Builder redirectUri(String v) { this.redirectUri = v; return this; }
        public Builder scopes(List<String> v) { this.scopes = new LinkedHashSet<>(v); return this; }
        public Builder pkce(boolean v) { this.pkce = v; return this; }

        public OAuthConfig build() {
            return new OAuthConfig(this);
        }
    }
}
