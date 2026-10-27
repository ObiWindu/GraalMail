/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonProviderTest {

    private ProtonProvider protonProvider;

    @BeforeEach
    public void setUp() {
        protonProvider = new ProtonProvider();
    }

    @Test
    void testProtonProviderCreation() {
        assertNotNull(protonProvider);
        assertEquals(ProviderType.PROTON, protonProvider.type());
        assertEquals("Proton Mail", protonProvider.displayName());
    }

    @Test
    void testProtonCapabilities() {
        ProviderCapabilities caps = protonProvider.capabilities();
        assertEquals(ProviderType.PROTON, caps.providerType());
        assertTrue(caps.authMethods().contains(AuthMethod.PASSWORD));
        assertTrue(caps.authMethods().contains(AuthMethod.BRIDGE));
        assertTrue(caps.serverSideSearch());
        assertFalse(caps.idleSupport());
        assertTrue(caps.flags());
        assertTrue(caps.attachments());
        assertFalse(caps.draftSync());
        assertFalse(caps.offlineActions()); // Proton API doesn't support offline actions
        assertFalse(caps.sendCapable()); // Sending is not supported yet
        assertTrue(caps.labels());
    }

    @Test
    void testPlatformSupport() {
        var caps = protonProvider.capabilities();
        assertTrue(caps.supportsPlatform("linux"));
        assertTrue(caps.supportsPlatform("android"));
    }

    @Test
    void testRestrictions() {
        var caps = protonProvider.capabilities();
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("bridge")));
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("android")));
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("send")));
    }

    @Test
    void testTransportModeDefaultsToApi() {
        assertEquals(ProtonTransportMode.API, ProtonTransportMode.fromSetting(null));
        assertEquals(ProtonTransportMode.API, ProtonTransportMode.fromSetting(""));
        assertEquals(ProtonTransportMode.BRIDGE, ProtonTransportMode.fromSetting("bridge"));
        assertEquals(ProtonTransportMode.BRIDGE, ProtonTransportMode.fromSetting("BRIDGE"));
        assertEquals(ProtonTransportMode.API, ProtonTransportMode.fromSetting("api"));
    }

    @Test
    void testImplementsAccountProvider() {
        assertTrue(AccountProvider.class.isAssignableFrom(ProtonProvider.class));
    }
}
