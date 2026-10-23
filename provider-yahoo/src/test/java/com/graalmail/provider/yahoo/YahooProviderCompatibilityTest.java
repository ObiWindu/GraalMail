/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.yahoo;

import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YahooProviderTest {

    private YahooProvider yahooProvider;

    @BeforeEach
    public void setUp() {
        yahooProvider = new YahooProvider();
    }

    @Test
    void testYahooProviderCreation() {
        assertNotNull(yahooProvider);
        assertEquals(ProviderType.YAHOO, yahooProvider.type());
        assertEquals("Yahoo Mail", yahooProvider.displayName());
    }

    @Test
    void testYahooCapabilities() {
        ProviderCapabilities caps = yahooProvider.capabilities();
        assertEquals(ProviderType.YAHOO, caps.providerType());
        assertTrue(caps.authMethods().contains(AuthMethod.OAUTH2));
        assertTrue(caps.authMethods().contains(AuthMethod.APP_PASSWORD));
        assertTrue(caps.serverSideSearch());
        assertTrue(caps.idleSupport());
        assertTrue(caps.flags());
        assertFalse(caps.labels()); // Yahoo doesn't use labels
        assertTrue(caps.attachments());
        assertTrue(caps.draftSync());
        assertTrue(caps.offlineActions());
        assertTrue(caps.sendCapable());
    }

    @Test
    void testPlatformSupport() {
        var caps = yahooProvider.capabilities();
        assertTrue(caps.supportsPlatform("linux"));
        assertTrue(caps.supportsPlatform("android"));
    }

    @Test
    void testRestrictions() {
        var caps = yahooProvider.capabilities();
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("app password")));
    }

    @Test
    void testImplementsAccountProvider() {
        assertTrue(AccountProvider.class.isAssignableFrom(YahooProvider.class));
    }
}