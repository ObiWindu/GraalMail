/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.imap;

import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AuthMethod;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImapProviderTest {

    private GenericImapProvider imapProvider;

    @BeforeEach
    public void setUp() {
        imapProvider = new GenericImapProvider();
    }

    @Test
    void testImapProviderCreation() {
        assertNotNull(imapProvider);
        assertEquals(ProviderType.GENERIC, imapProvider.type());
    }

    @Test
    void testImapCapabilities() {
        ProviderCapabilities caps = imapProvider.capabilities();
        assertEquals(ProviderType.GENERIC, caps.providerType());
        assertTrue(caps.authMethods().contains(AuthMethod.PASSWORD));
        assertTrue(caps.serverSideSearch());
        assertTrue(caps.idleSupport());
        assertTrue(caps.flags());
        assertTrue(caps.attachments());
        assertTrue(caps.draftSync());
    }

    @Test
    void testImplementsAccountProvider() {
        assertTrue(AccountProvider.class.isAssignableFrom(GenericImapProvider.class));
    }
}