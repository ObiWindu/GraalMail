/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.yahoo;

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
 * Verifies the server configuration and the sign in paths of the Yahoo
 * provider without contacting Yahoo.
 */
class YahooProviderTest {

    private static AccountSetup appPasswordSetup() {
        return AccountSetup.builder(AuthMethod.APP_PASSWORD)
                .email("user@yahoo.com")
                .password("user@yahoo.com", "app-password")
                .build();
    }

    @Test
    void usesTheYahooServersOverTls() {
        ImapServerConfig imap = YahooProvider.imapConfig(appPasswordSetup(), false);
        assertEquals("imap.mail.yahoo.com", imap.host());
        assertEquals(993, imap.port());
        assertEquals(SecurityMode.SSL_TLS, imap.securityMode());
        assertEquals(AuthMethod.APP_PASSWORD, imap.authMechanism());

        SmtpServerConfig smtp = YahooProvider.smtpConfig(appPasswordSetup(), false);
        assertEquals("smtp.mail.yahoo.com", smtp.host());
        assertEquals(465, smtp.port());
        assertEquals(SecurityMode.SSL_TLS, smtp.securityMode());
    }

    @Test
    void switchesToOauthWhenTheAccountIsAuthorizedWithIt() {
        ImapServerConfig imap = YahooProvider.imapConfig(appPasswordSetup(), true);
        assertEquals(AuthMethod.OAUTH2, imap.authMechanism());
        assertEquals(AuthMethod.OAUTH2, YahooProvider.smtpConfig(appPasswordSetup(), true)
                .authMechanism());
    }

    @Test
    void appendsTheSentMessageToTheSentFolder() {
        Map<String, String> settings = YahooProvider.folderSettings(Map.of("custom", "value"));
        assertEquals("value", settings.get("custom"));
        assertEquals("true", settings.get("sent.appendAfterSend"));
    }

    @Test
    void refusesASessionWithoutAnAppPassword() {
        YahooProvider provider = new YahooProvider();
        AccountSetup empty = AccountSetup.builder(AuthMethod.APP_PASSWORD)
                .email("user@yahoo.com")
                .build();
        ProviderException failure = assertThrows(ProviderException.class,
                () -> provider.openSession(empty));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, failure.code());
    }

    @Test
    void requiresAClientIdForTheOauthFlow() {
        ProviderException failure = assertThrows(ProviderException.class,
                () -> YahooOAuthConfig.forAccount(appPasswordSetup()));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, failure.code());
    }

    @Test
    void declaresTheAppPasswordAndTheOauthSignIn() {
        YahooProvider provider = new YahooProvider();
        assertTrue(provider.capabilities().supportsAuthMethod(AuthMethod.APP_PASSWORD));
        assertTrue(provider.capabilities().supportsAuthMethod(AuthMethod.OAUTH2));
        assertTrue(provider.capabilities().sendCapable());
    }
}
