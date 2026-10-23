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
        assertEquals("Proton Mail (via Proton Mail Bridge)", protonProvider.displayName());
    }

    @Test
    void testProtonCapabilities() {
        ProviderCapabilities caps = protonProvider.capabilities();
        assertEquals(ProviderType.PROTON, caps.providerType());
        assertTrue(caps.authMethods().contains(AuthMethod.BRIDGE));
        assertTrue(caps.serverSideSearch());
        assertTrue(caps.idleSupport());
        assertTrue(caps.flags());
        assertTrue(caps.attachments());
        assertTrue(caps.draftSync());
        assertFalse(caps.offlineActions()); // Proton Bridge doesn't support offline actions
        assertTrue(caps.sendCapable());
    }

    @Test
    void testPlatformSupport() {
        var caps = protonProvider.capabilities();
        assertTrue(caps.supportsPlatform("linux"));
        assertFalse(caps.supportsPlatform("android"));
    }

    @Test
    void testRestrictions() {
        var caps = protonProvider.capabilities();
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("bridge")));
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("android")));
        assertTrue(caps.restrictions().stream().anyMatch(r -> r.toLowerCase().contains("paid")));
    }

    @Test
    void testImplementsAccountProvider() {
        assertTrue(AccountProvider.class.isAssignableFrom(ProtonProvider.class));
    }
}