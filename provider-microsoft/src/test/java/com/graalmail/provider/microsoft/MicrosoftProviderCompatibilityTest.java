/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderType;
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
}