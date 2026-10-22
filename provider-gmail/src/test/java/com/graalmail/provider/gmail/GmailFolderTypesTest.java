/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.gmail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.FolderType;
import org.junit.jupiter.api.Test;

/**
 * Verifies the mapping of the Gmail labels to the neutral folder roles.
 */
class GmailFolderTypesTest {

    @Test
    void mapsTheSystemLabels() {
        assertEquals(FolderType.INBOX, GmailFolderTypes.fromLabel("INBOX"));
        assertEquals(FolderType.SENT, GmailFolderTypes.fromLabel("[Gmail]/Sent Mail"));
        assertEquals(FolderType.DRAFTS, GmailFolderTypes.fromLabel("[Gmail]/Drafts"));
        assertEquals(FolderType.TRASH, GmailFolderTypes.fromLabel("[Gmail]/Trash"));
        assertEquals(FolderType.SPAM, GmailFolderTypes.fromLabel("[Gmail]/Spam"));
        assertEquals(FolderType.ARCHIVE, GmailFolderTypes.fromLabel("[Gmail]/All Mail"));
    }

    @Test
    void mapsUserLabelsToCustomFolders() {
        assertEquals(FolderType.CUSTOM, GmailFolderTypes.fromLabel("[Projects]"));
        assertEquals(FolderType.CUSTOM, GmailFolderTypes.fromLabel("[Gmail]/Starred"));
    }

    @Test
    void prefersTheSpecialUseAttributes() {
        assertEquals(FolderType.SENT, GmailFolderTypes.DEFAULT.resolve("[Gmail]/Sent Mail",
                java.util.List.of("\\Sent")));
        assertEquals(FolderType.INBOX, GmailFolderTypes.DEFAULT.resolve("INBOX",
                java.util.List.of("\\HasNoChildren")));
    }

    @Test
    void declaresTheProviderCapabilities() {
        GmailProvider provider = new GmailProvider();
        assertTrue(provider.capabilities().labels());
        assertTrue(provider.capabilities().supportsAuthMethod(
                com.graalmail.provider.api.AuthMethod.OAUTH2));
        assertTrue(provider.capabilities().supportsPlatform("linux"));
        assertTrue(provider.capabilities().supportsPlatform("android"));
    }
}
