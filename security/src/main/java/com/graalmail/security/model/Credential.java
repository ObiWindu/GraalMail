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
    /**
     * A second secret, used only by providers that need two passwords.
     * Proton accounts in two-password mode keep the mailbox password here
     * while {@link #secret} holds the login password.
     */
    private final String keySecret;

    public Credential(String username, String secret, TokenSet tokens) {
        this(username, secret, tokens, "");
    }

    public Credential(String username, String secret, TokenSet tokens, String keySecret) {
        this.username = username == null ? "" : username;
        this.secret = secret == null ? "" : secret;
        this.tokens = tokens == null ? TokenSet.empty() : tokens;
        this.keySecret = keySecret == null ? "" : keySecret;
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
    /** The second secret, or an empty string when the provider needs none. */
    public String keySecret() { return keySecret; }

    public boolean hasPassword() {
        return !secret.isBlank();
    }

    public boolean hasKeySecret() {
        return !keySecret.isBlank();
    }

    @Override
    public String toString() {
        return "Credential{username='" + username + "', hasPassword=" + hasPassword()
                + ", hasKeySecret=" + hasKeySecret()
                + ", hasTokens=" + (!tokens.accessToken().isBlank() || tokens.hasRefreshToken()) + "}";
    }
}
