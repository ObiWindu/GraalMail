/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AuthMethod;
import java.nio.file.Path;

/**
 * Immutable configuration of the SMTP side of an account.
 *
 * <p>As for IMAP, the security mode is derived from the port when it is not
 * given explicitly: port 465 implies implicit TLS and port 587 implies
 * STARTTLS. Selecting {@code none} on an implicit TLS port is rejected so that
 * a user selection can never silently downgrade the connection.</p>
 */
public final class SmtpServerConfig {

    /** Port that is used when the caller does not provide one. */
    public static final int DEFAULT_PORT = 587;

    private final String host;
    private final int port;
    private final SecurityMode securityMode;
    private final String username;
    private final AuthMethod authMechanism;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Path clientCertificate;
    private final String clientCertificatePassword;
    private final Path trustCertificate;

    private SmtpServerConfig(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.securityMode = b.securityMode == null ? defaultModeFor(b.port) : b.securityMode;
        this.username = b.username;
        this.authMechanism = b.authMechanism;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.readTimeoutMs = b.readTimeoutMs;
        this.clientCertificate = b.clientCertificate;
        this.clientCertificatePassword = b.clientCertificatePassword;
        this.trustCertificate = b.trustCertificate;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The security mode implied by a well known SMTP port.
     *
     * @param port the port
     * @return the implied mode
     */
    public static SecurityMode defaultModeFor(int port) {
        return switch (port) {
            case 465 -> SecurityMode.SSL_TLS;
            case 587, 143, 25 -> SecurityMode.STARTTLS;
            default -> SecurityMode.NONE;
        };
    }

    public String host() { return host; }
    public int port() { return port; }
    public SecurityMode securityMode() { return securityMode; }
    public String username() { return username; }
    public AuthMethod authMechanism() { return authMechanism; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public Path clientCertificate() { return clientCertificate; }
    public String clientCertificatePassword() { return clientCertificatePassword; }
    public Path trustCertificate() { return trustCertificate; }

    /** True when the connection is encrypted in one way or another. */
    public boolean isEncrypted() {
        return securityMode != SecurityMode.NONE;
    }

    private void validate() {
        if (host == null || host.isBlank()) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The SMTP host name is missing. Set smtp.host in the account settings.", false);
        }
        if (port < 1 || port > 65535) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The SMTP port " + port + " is not a valid port number.", false);
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The SMTP connection and read timeouts must be positive.", false);
        }
        if (authMechanism != AuthMethod.OAUTH2 && (username == null || username.isBlank())) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The SMTP user name is missing. Set auth.username in the account settings.", false);
        }
        if (securityMode == SecurityMode.NONE && port == 465) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Refusing to disable encryption on the implicit TLS port 465. "
                            + "Choose ssl or starttls instead of none.", false);
        }
        if (securityMode == SecurityMode.STARTTLS && port == 465) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "STARTTLS cannot be used on the implicit TLS port 465. Choose ssl.", false);
        }
    }

    /** Mutable builder for {@link SmtpServerConfig}. */
    public static final class Builder {
        private String host = "";
        private int port = DEFAULT_PORT;
        private SecurityMode securityMode;
        private String username = "";
        private AuthMethod authMechanism = AuthMethod.PASSWORD;
        private int connectTimeoutMs = 15_000;
        private int readTimeoutMs = 30_000;
        private Path clientCertificate;
        private String clientCertificatePassword = "";
        private Path trustCertificate;

        private Builder() {
        }

        public Builder host(String v) { this.host = v == null ? "" : v.trim(); return this; }
        public Builder port(int v) { this.port = v; return this; }
        public Builder securityMode(SecurityMode v) { this.securityMode = v; return this; }
        public Builder username(String v) { this.username = v == null ? "" : v.trim(); return this; }
        public Builder authMechanism(AuthMethod v) { this.authMechanism = v; return this; }
        public Builder connectTimeoutMs(int v) { this.connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { this.readTimeoutMs = v; return this; }

        public Builder clientCertificate(Path v) { this.clientCertificate = v; return this; }

        public Builder clientCertificatePassword(String v) {
            this.clientCertificatePassword = v == null ? "" : v;
            return this;
        }

        public Builder trustCertificate(Path v) { this.trustCertificate = v; return this; }

        public SmtpServerConfig build() {
            SmtpServerConfig config = new SmtpServerConfig(this);
            config.validate();
            return config;
        }
    }
}
