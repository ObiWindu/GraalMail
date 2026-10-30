/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.imap.ImapServerConfig;
import com.graalmail.provider.imap.SecurityMode;
import com.graalmail.provider.imap.SmtpServerConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the server configuration and the label mapping of the Gmail
 * provider without contacting Google.
 */
class GmailProviderTest {

    private static AccountSetup setup(AuthMethod method) {
        return AccountSetup.builder(method)
                .email("user@gmail.com")
                .password("user@gmail.com", "secret")
                .build();
    }

    @Test
    void usesTheGmailServersOverTls() {
        ImapServerConfig imap = GmailProvider.imapConfig(setup(AuthMethod.OAUTH2));
        assertEquals("imap.gmail.com", imap.host());
        assertEquals(993, imap.port());
        assertEquals(SecurityMode.SSL_TLS, imap.securityMode());
        assertEquals(AuthMethod.OAUTH2, imap.authMechanism());
        assertEquals("user@gmail.com", imap.username());
        assertTrue(imap.isEncrypted());

        SmtpServerConfig smtp = GmailProvider.smtpConfig(setup(AuthMethod.OAUTH2));
        assertEquals("smtp.gmail.com", smtp.host());
        assertEquals(587, smtp.port());
        assertEquals(SecurityMode.STARTTLS, smtp.securityMode());
        assertEquals(AuthMethod.OAUTH2, smtp.authMechanism());
    }

    @Test
    void mapsTheGmailLabelsToTheFolderSettings() {
        Map<String, String> settings = GmailProvider.folderSettings(Map.of("custom", "value"));
        assertEquals("value", settings.get("custom"));
        assertEquals(GmailFolderTypes.LABEL_SENT, settings.get("sent.folder"));
        assertEquals(GmailFolderTypes.LABEL_DRAFT, settings.get("drafts.folder"));
        assertEquals(GmailFolderTypes.LABEL_TRASH, settings.get("trash.folder"));
        assertEquals(GmailFolderTypes.LABEL_SPAM, settings.get("spam.folder"));
        assertEquals(GmailFolderTypes.LABEL_INBOX, settings.get("inbox.folder"));
        assertEquals("true", settings.get("sent.appendAfterSend"));
    }

    @Test
    void usesPlainPasswordSignInForAppPasswords() {
        ImapServerConfig imap = GmailProvider.imapConfig(setup(AuthMethod.APP_PASSWORD));
        assertEquals("imap.gmail.com", imap.host());
        assertEquals(AuthMethod.APP_PASSWORD, imap.authMechanism());
        assertTrue(imap.isEncrypted());

        SmtpServerConfig smtp = GmailProvider.smtpConfig(setup(AuthMethod.APP_PASSWORD));
        assertEquals("smtp.gmail.com", smtp.host());
        assertEquals(SecurityMode.STARTTLS, smtp.securityMode());
        assertEquals(AuthMethod.APP_PASSWORD, smtp.authMechanism());
    }

    @Test
    void refusesAnAppPasswordSignInWithoutAPassword() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.APP_PASSWORD)
                .email("user@gmail.com")
                .build();
        GmailProvider provider = new GmailProvider();
        ProviderException failure = assertThrows(ProviderException.class,
                () -> provider.openSession(setup));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, failure.code());
    }

    @Test
    void refusesAnOauthSignInWithoutTokens() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.OAUTH2)
                .email("user@gmail.com")
                .setting("oauth.clientId", "client-id")
                .setting("oauth.redirectUri", "http://localhost:8080/callback")
                .build();
        GmailProvider provider = new GmailProvider();
        ProviderException failure = assertThrows(ProviderException.class,
                () -> provider.openSession(setup));
        assertEquals(ErrorCode.AUTH_EXPIRED, failure.code());
    }

    @Test
    void requiresAClientIdForTheOauthFlow() {
        ProviderException failure = assertThrows(ProviderException.class,
                () -> GmailOAuthConfig.forAccount(setup(AuthMethod.OAUTH2)));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, failure.code());
    }

    @Test
    void buildsTheOauthConfigurationFromTheAccountSettings() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.OAUTH2)
                .email("user@gmail.com")
                .setting("oauth.clientId", "client-id")
                .setting("oauth.redirectUri", "http://localhost:8080/callback")
                .build();
        var config = GmailOAuthConfig.forAccount(setup);
        assertEquals("client-id", config.clientId());
        assertEquals("http://localhost:8080/callback", config.redirectUri());
        assertEquals(GmailOAuthConfig.AUTHORIZATION_ENDPOINT, config.authorizationEndpoint());
        assertEquals(GmailOAuthConfig.TOKEN_ENDPOINT, config.tokenEndpoint());
        assertTrue(config.scopes().contains(GmailOAuthConfig.SCOPE_MAIL));
        assertTrue(config.usePkce());
    }
}
