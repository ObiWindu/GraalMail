/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import java.util.Locale;

/** How a user proves their identity for a provider. */
public enum AuthMethod {
    /** OAuth 2.0 authorization code flow, typically with PKCE. */
    OAUTH2("OAuth 2.0"),
    /** Plain username and password. */
    PASSWORD("Password"),
    /** Provider specific app password, for example Yahoo. */
    APP_PASSWORD("App password"),
    /** Local bridge process that performs authentication on the user's behalf. */
    BRIDGE("Local bridge application"),
    /** No credentials required by this client; the user acts in another app. */
    EXTERNAL_APP("Handled by the provider's own app");

    private final String displayName;

    AuthMethod(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
