/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.model;

/** Credentials for one account. Secrets never leave the credential store. */
public final class Credential {
    private final String username;
    private final String secret;
    private final TokenSet tokens;

    public Credential(String username, String secret, TokenSet tokens) {
        this.username = username == null ? "" : username;
        this.secret = secret == null ? "" : secret;
        this.tokens = tokens == null ? TokenSet.empty() : tokens;
    }

    public static Credential password(String username, String secret) {
        return new Credential(username, secret, TokenSet.empty());
    }

    public static Credential oauth(String username, TokenSet tokens) {
        return new Credential(username, "", tokens);
    }

    public String username() { return username; }
    public String secret() { return secret; }
    public TokenSet tokens() { return tokens; }

    public boolean hasPassword() {
        return !secret.isBlank();
    }

    @Override
    public String toString() {
        return "Credential{username='" + username + "', hasPassword=" + hasPassword()
                + ", hasTokens=" + (!tokens.accessToken().isBlank() || tokens.hasRefreshToken()) + "}";
    }
}
