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
 * Immutable configuration of the IMAP side of an account.
 *
 * <p>The security mode is derived from the port when it is not given
 * explicitly: port 465 and 993 imply implicit TLS, port 143 and 587 imply
 * STARTTLS. An explicit downgrade, for example selecting {@code none} on the
 * implicit TLS port 993, is rejected instead of silently connecting in clear
 * text.</p>
 */
public final class ImapServerConfig {

    /** Port that is used when the caller does not provide one. */
    public static final int DEFAULT_PORT = 993;

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

    private ImapServerConfig(Builder b) {
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
     * The security mode implied by a well known IMAP port.
     *
     * @param port the port
     * @return the implied mode
     */
    public static SecurityMode defaultModeFor(int port) {
        return switch (port) {
            case 465, 993 -> SecurityMode.SSL_TLS;
            case 143, 587 -> SecurityMode.STARTTLS;
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
                    "The IMAP host name is missing. Set imap.host in the account settings.", false);
        }
        if (port < 1 || port > 65535) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The IMAP port " + port + " is not a valid port number.", false);
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The IMAP connection and read timeouts must be positive.", false);
        }
        if (authMechanism != AuthMethod.OAUTH2 && (username == null || username.isBlank())) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "The IMAP user name is missing. Set auth.username in the account settings.", false);
        }
        if (securityMode == SecurityMode.NONE && (port == 465 || port == 993)) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Refusing to disable encryption on the implicit TLS port " + port
                            + ". Choose ssl or starttls instead of none.", false);
        }
        if (securityMode == SecurityMode.STARTTLS && (port == 465 || port == 993)) {
            throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "STARTTLS cannot be used on the implicit TLS port " + port + ". Choose ssl.", false);
        }
    }

    /** Mutable builder for {@link ImapServerConfig}. */
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

        /** Path to a PKCS#12 key store that holds the client certificate and key. */
        public Builder clientCertificate(Path v) { this.clientCertificate = v; return this; }

        public Builder clientCertificatePassword(String v) {
            this.clientCertificatePassword = v == null ? "" : v;
            return this;
        }

        /** Path to a PEM or DER certificate that is additionally trusted. */
        public Builder trustCertificate(Path v) { this.trustCertificate = v; return this; }

        public ImapServerConfig build() {
            ImapServerConfig config = new ImapServerConfig(this);
            config.validate();
            return config;
        }
    }
}
