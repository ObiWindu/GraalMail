/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AuthMethod;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import java.security.KeyStore;
import java.util.Properties;
import javax.net.ssl.SSLContext;

/**
 * Builds the {@link Session} used to talk to an IMAP or SMTP server.
 *
 * <p>Transport security follows the configured {@link SecurityMode}. TLS
 * verification is always enabled with the system trust store. A custom trust
 * store can be supplied for providers that publish their own certificate
 * authority (for example a locally running Proton Mail Bridge).</p>
 */
public final class MailSessions {

    private MailSessions() {
    }

    /**
     * Creates a session for an IMAP connection.
     *
     * @param config the IMAP configuration
     * @param password the plain password, may be {@code null} for OAuth
     * @return the mail session
     */
    public static Session imap(ImapServerConfig config, String password) {
        Properties properties = baseProperties();
        properties.put("mail.imap.host", config.host());
        properties.put("mail.imap.port", String.valueOf(config.port()));
        properties.put("mail.imap.connectiontimeout", String.valueOf(config.connectTimeoutMs()));
        properties.put("mail.imap.timeout", String.valueOf(config.readTimeoutMs()));
        applyTls(properties, "mail.imap", config.securityMode(), config.trustCertificate(),
                config.clientCertificate(), config.clientCertificatePassword());
        if (config.authMechanism() == AuthMethod.OAUTH2) {
            applyOauth(properties, "mail.imap");
        }
        return session(properties, config.username(), password);
    }

    /**
     * Enables the XOAUTH2 SASL mechanism for the given protocol prefix.
     *
     * <p>The plain LOGIN and PLAIN mechanisms are disabled so that the access
     * token is the only credential that can be used.</p>
     *
     * @param properties the mail session properties
     * @param prefix the protocol prefix, for example {@code mail.imap}
     */
    private static void applyOauth(Properties properties, String prefix) {
        properties.put(prefix + ".auth.mechanisms", "XOAUTH2");
        properties.put(prefix + ".sasl.enable", "true");
        properties.put(prefix + ".sasl.mechanisms", "XOAUTH2");
        properties.put(prefix + ".auth.login.disable", "true");
        properties.put(prefix + ".auth.plain.disable", "true");
        properties.put(prefix + ".auth.xoauth2.disableFailureLog", "true");
    }

    /**
     * Creates a session for an SMTP submission.
     *
     * @param config the SMTP configuration
     * @param password the plain password, may be {@code null} for OAuth
     * @return the mail session
     */
    public static Session smtp(SmtpServerConfig config, String password) {
        Properties properties = baseProperties();
        properties.put("mail.smtp.host", config.host());
        properties.put("mail.smtp.port", String.valueOf(config.port()));
        properties.put("mail.smtp.connectiontimeout", String.valueOf(config.connectTimeoutMs()));
        properties.put("mail.smtp.timeout", String.valueOf(config.readTimeoutMs()));
        properties.put("mail.smtp.sendpartial", "true");
        applyTls(properties, "mail.smtp", config.securityMode(), config.trustCertificate(),
                config.clientCertificate(), config.clientCertificatePassword());
        if (config.authMechanism() == AuthMethod.OAUTH2) {
            applyOauth(properties, "mail.smtp");
        }
        return session(properties, config.username(), password);
    }

    /**
     * Creates a session that only carries transport settings, used for OAuth
     * based connections where the credentials are supplied per command.
     *
     * @param config the IMAP configuration
     * @return the mail session
     */
    public static Session imapWithoutCredentials(ImapServerConfig config) {
        return imap(config, null);
    }

    /**
     * Creates a session that only carries transport settings, used for OAuth
     * based SMTP submission where the credentials are supplied per command.
     *
     * @param config the SMTP configuration
     * @return the mail session
     */
    public static Session smtpWithoutCredentials(SmtpServerConfig config) {
        return smtp(config, null);
    }

    private static Properties baseProperties() {
        Properties properties = new Properties();
        properties.put("mail.mime.charset", "UTF-8");
        properties.put("mail.imap.ignorebodystructure", "true");
        return properties;
    }

    private static Session session(Properties properties, String username, String password) {
        if (username == null || username.isBlank()) {
            return Session.getInstance(properties);
        }
        return Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password == null ? "" : password);
            }
        });
    }

    private static void applyTls(Properties properties, String prefix, SecurityMode mode,
                                  java.nio.file.Path trustCertificate,
                                  java.nio.file.Path clientCertificate,
                                  String clientCertificatePassword) {
        switch (mode) {
            case SSL_TLS -> {
                properties.put(prefix + ".ssl.enable", "true");
                properties.put(prefix + ".ssl.checkserveridentity", "true");
                // Use system default trust store - do NOT set ssl.trust="*"
            }
            case STARTTLS -> {
                properties.put(prefix + ".starttls.enable", "true");
                properties.put(prefix + ".starttls.required", "true");
                properties.put(prefix + ".ssl.checkserveridentity", "true");
                // Use system default trust store - do NOT set ssl.trust="*"
            }
            case NONE -> {
                // The caller validated that an unencrypted connection is acceptable.
            }
            default -> throw new ProviderException(ErrorCode.CONFIGURATION_ERROR,
                    "Unsupported security mode " + mode, false);
        }
        if (trustCertificate != null) {
            try {
                SSLContext context = com.graalmail.security.tls.TlsContexts.withCertificate(trustCertificate);
                properties.put(prefix + ".ssl.socketFactory", context.getSocketFactory());
                properties.put(prefix + ".ssl.socketFactory.class", context.getSocketFactory().getClass().getName());
            } catch (Exception e) {
                throw new ProviderException(ErrorCode.TLS_ERROR,
                        "The additional trusted certificate could not be loaded.", false, e);
            }
        }
        if (clientCertificate != null) {
            try {
                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(clientCertificate)) {
                    keyStore.load(in, clientCertificatePassword.toCharArray());
                }
                SSLContext context = SSLContext.getInstance("TLS");
                javax.net.ssl.KeyManagerFactory keyManagers =
                        javax.net.ssl.KeyManagerFactory.getInstance(
                                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(keyStore, clientCertificatePassword.toCharArray());
                context.init(keyManagers.getKeyManagers(), null, null);
                properties.put(prefix + ".ssl.socketFactory", context.getSocketFactory());
                properties.put(prefix + ".ssl.socketFactory.class", context.getSocketFactory().getClass().getName());
            } catch (Exception e) {
                throw new ProviderException(ErrorCode.TLS_ERROR,
                        "The client certificate could not be loaded. Provide a PKCS#12 key store.",
                        false, e);
            }
        }
    }
}
