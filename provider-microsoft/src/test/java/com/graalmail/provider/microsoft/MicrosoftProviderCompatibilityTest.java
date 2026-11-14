/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.imap.ImapServerConfig;
import com.graalmail.provider.imap.SecurityMode;
import com.graalmail.provider.imap.SmtpServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MicrosoftProviderTest {

    private MicrosoftProvider microsoftProvider;

    @BeforeEach
    public void setUp() {
        microsoftProvider = new MicrosoftProvider();
    }

    @Test
    void testMicrosoftProviderCreation() {
        assertNotNull(microsoftProvider);
        assertEquals(ProviderType.MICROSOFT, microsoftProvider.type());
        assertEquals("Outlook / Hotmail / Microsoft 365", microsoftProvider.displayName());
    }

    @Test
    void testMicrosoftCapabilities() {
        ProviderCapabilities caps = microsoftProvider.capabilities();
        assertEquals(ProviderType.MICROSOFT, caps.providerType());
        assertTrue(caps.authMethods().contains(AuthMethod.OAUTH2));
        assertTrue(caps.authMethods().contains(AuthMethod.PASSWORD),
                "Microsoft accounts must be addable with email + password");
        assertTrue(caps.serverSideSearch());
        assertTrue(caps.idleSupport()); // IMAP supports IDLE
        assertTrue(caps.flags());
        assertFalse(caps.labels()); // Microsoft uses categories, not labels
        assertTrue(caps.attachments());
        assertTrue(caps.draftSync());
        assertTrue(caps.offlineActions());
        assertTrue(caps.sendCapable());
    }

    @Test
    void testPlatformSupport() {
        var caps = microsoftProvider.capabilities();
        assertTrue(caps.supportsPlatform("linux"));
        assertTrue(caps.supportsPlatform("android"));
    }

    @Test
    void testRestrictions() {
        var caps = microsoftProvider.capabilities();
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("imap")));
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("tenant")));
    }

    @Test
    void testImplementsAccountProvider() {
        assertTrue(AccountProvider.class.isAssignableFrom(MicrosoftProvider.class));
    }

    @Test
    void testPasswordConfigsUseBasicAuthOnMicrosoftEndpoints() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@outlook.com")
                .password("user@outlook.com", "secret")
                .build();
        ImapServerConfig imap = MicrosoftProvider.passwordImapConfig(setup);
        assertEquals(MicrosoftHosts.IMAP_HOST, imap.host());
        assertEquals(MicrosoftHosts.IMAP_PORT, imap.port());
        assertEquals(SecurityMode.SSL_TLS, imap.securityMode());
        assertEquals(AuthMethod.PASSWORD, imap.authMechanism());
        assertEquals("user@outlook.com", imap.username());
        SmtpServerConfig smtp = MicrosoftProvider.passwordSmtpConfig(setup);
        assertEquals(MicrosoftHosts.SMTP_HOST, smtp.host());
        assertEquals(MicrosoftHosts.SMTP_PORT_STARTTLS, smtp.port());
        assertEquals(SecurityMode.STARTTLS, smtp.securityMode());
        assertEquals(AuthMethod.PASSWORD, smtp.authMechanism());
    }

    @Test
    void testPasswordSessionWithoutSecretIsRejectedBeforeConnecting() {
        AccountSetup setup = AccountSetup.builder(AuthMethod.PASSWORD)
                .email("user@outlook.com")
                .credential(com.graalmail.security.model.Credential.password("user@outlook.com", ""))
                .build();
        ProviderException error = assertThrows(ProviderException.class,
                () -> microsoftProvider.openSession(setup));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, error.code());
    }
}