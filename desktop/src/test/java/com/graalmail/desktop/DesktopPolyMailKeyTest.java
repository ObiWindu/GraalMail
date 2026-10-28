/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mailbox signing key identifies this node to senders verifying storage
 * receipts, so it must survive restarts — and a corrupt key file must never
 * wedge the service (it regenerates).
 */
class DesktopPolyMailKeyTest {

    @TempDir
    Path temp;

    @Test
    void keyIsStableAcrossLoads() throws Exception {
        KeyPair first = DesktopPolyMailService.mailboxSigningKey(temp);
        KeyPair second = DesktopPolyMailService.mailboxSigningKey(temp);
        assertArrayEquals(first.getPublic().getEncoded(), second.getPublic().getEncoded());
        assertArrayEquals(first.getPrivate().getEncoded(), second.getPrivate().getEncoded());
        assertTrue(Files.isRegularFile(temp.resolve("polymail-mailbox-signing.key")));
    }

    @Test
    void corruptKeyFileRegenerates() throws Exception {
        Files.write(temp.resolve("polymail-mailbox-signing.key"), new byte[]{1, 2, 3});
        KeyPair regenerated = assertDoesNotThrow(
                () -> DesktopPolyMailService.mailboxSigningKey(temp));
        assertTrue(regenerated.getPublic().getEncoded().length > 0);
        // The regenerated key persists from here on.
        KeyPair reloaded = DesktopPolyMailService.mailboxSigningKey(temp);
        assertArrayEquals(regenerated.getPublic().getEncoded(), reloaded.getPublic().getEncoded());
    }

    @Test
    void mailboxPeersRoundTripThroughSettings() {
        com.graalmail.storage.SqlMailStore store = new com.graalmail.storage.SqlMailStore(
                new com.graalmail.storage.JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        try {
            DesktopSettings settings = new DesktopSettings(store);
            assertEquals("", settings.polyMailMailboxPeers());
            settings.polyMailMailboxPeers("mailbox1.example.com:9000, mailbox2.example.com:9001");
            assertEquals("mailbox1.example.com:9000, mailbox2.example.com:9001",
                    new DesktopSettings(store).polyMailMailboxPeers());
            assertEquals(2, com.graalmail.p2p.PeerAddress.parseList(
                    settings.polyMailMailboxPeers()).size());
        } finally {
            store.close();
        }
    }
}
