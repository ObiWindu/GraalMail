/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.oauth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** A pending authorization request; the UI opens {@link #authorizationUrl()}. */
public final class AuthorizationRequest {

    private final String authorizationUrl;
    private final String state;
    private final String codeVerifier;
    private final String redirectUri;

    public AuthorizationRequest(String authorizationUrl, String state, String codeVerifier,
                                String redirectUri) {
        this.authorizationUrl = authorizationUrl;
        this.state = state;
        this.codeVerifier = codeVerifier;
        this.redirectUri = redirectUri;
    }

    public static AuthorizationRequest create(OAuthConfig config) {
        String state = randomToken();
        String verifier = config.usePkce() ? Pkce.newCodeVerifier() : "";
        StringBuilder url = new StringBuilder(config.authorizationEndpoint());
        url.append(config.authorizationEndpoint().contains("?") ? '&' : '?');
        url.append("response_type=code");
        url.append("&client_id=").append(enc(config.clientId()));
        url.append("&redirect_uri=").append(enc(config.redirectUri()));
        url.append("&state=").append(enc(state));
        if (!config.scopes().isEmpty()) {
            url.append("&scope=").append(enc(String.join(" ", config.scopes())));
        }
        if (config.usePkce()) {
            url.append("&code_challenge=").append(enc(Pkce.challengeS256(verifier)));
            url.append("&code_challenge_method=S256");
        }
        if (config.hasClientSecret()) {
            url.append("&client_secret=").append(enc(config.clientSecret()));
        }
        return new AuthorizationRequest(url.toString(), state, verifier, config.redirectUri());
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public String authorizationUrl() { return authorizationUrl; }
    public String state() { return state; }
    public String codeVerifier() { return codeVerifier; }
    public String redirectUri() { return redirectUri; }
}
