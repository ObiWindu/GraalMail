/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonMessageCryptoTest {

    private static final char[] PASSWORD = "mailbox-secret".toCharArray();
    private static final byte[] PASSWORD_BYTES = "mailbox-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MIME = ("Subject: Hello\r\nContent-Type: text/plain\r\n\r\nBody text here.")
            .getBytes(StandardCharsets.UTF_8);

    private static List<ProtonCrypto.UnlockedKey> unlock(PgpFixtures.KeyMaterial material) {
        return ProtonKeyUnlock.unlock(
                List.of(new ProtonApiClient.PrivateKey("k1", "fp", 1, 1,
                        material.armoredSecret(), "", "")),
                java.util.Map.of(), PASSWORD_BYTES, List.of());
    }

    @Test
    void decryptsPlainAndCompressedMessages() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey(PASSWORD);
        List<ProtonCrypto.UnlockedKey> keys = unlock(material);
        assertArrayEquals(MIME, ProtonMessageCrypto.decryptArmored(
                PgpFixtures.encrypt(material.publicKey(), MIME, false), keys));
        assertArrayEquals(MIME, ProtonMessageCrypto.decryptArmored(
                PgpFixtures.encrypt(material.publicKey(), MIME, true), keys));
    }

    @Test
    void wrongKeyCannotRead() throws Exception {
        PgpFixtures.KeyMaterial recipient = PgpFixtures.generateKey(PASSWORD);
        PgpFixtures.KeyMaterial other = PgpFixtures.generateKey("other-pass".toCharArray());
        String armored = PgpFixtures.encrypt(recipient.publicKey(), MIME, true);
        assertThrows(Exception.class, () -> ProtonMessageCrypto.decryptArmored(armored, unlock(other)));
    }

    @Test
    void tamperedMessageFails() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey(PASSWORD);
        String armored = PgpFixtures.encrypt(material.publicKey(), MIME, false);
        // Flip a base64 character inside the payload (not in the armor headers).
        String[] lines = armored.split("\n");
        int target = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank() && !lines[i].startsWith("-----") && !lines[i].contains(":")) {
                target = i;
                break;
            }
        }
        assertTrue(target >= 0);
        char[] chars = lines[target].toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        lines[target] = new String(chars);
        String tampered = String.join("\n", lines);
        assertThrows(Exception.class,
                () -> ProtonMessageCrypto.decryptArmored(tampered, unlock(material)));
    }

    @Test
    void emptyAndGarbageInputFails() {
        PgpFixtures.KeyMaterial material;
        try {
            material = PgpFixtures.generateKey(PASSWORD);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<ProtonCrypto.UnlockedKey> keys = unlock(material);
        assertThrows(Exception.class, () -> ProtonMessageCrypto.decryptArmored("", keys));
        assertThrows(Exception.class, () -> ProtonMessageCrypto.decryptArmored("hello", keys));
        assertThrows(Exception.class, () -> ProtonMessageCrypto.decryptArmored(
                PgpFixtures.encrypt(material.publicKey(), MIME, false), List.of()));
    }
}
