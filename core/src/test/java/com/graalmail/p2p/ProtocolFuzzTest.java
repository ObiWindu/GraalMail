package com.graalmail.p2p;

import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;
import com.graalmail.p2p.protocol.GpmpMessage;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial input handling (spec sections 18, 19.5): protocol parsers,
 * ciphertext handling and hash checks must fail closed on hostile input —
 * typed errors, never silent trust, unbounded allocation, or hangs.
 */
class ProtocolFuzzTest {
    private final EncryptionService crypto = new DefaultEncryptionService();
    private final Random random = new Random(0x5EED);

    @Test
    void envelopeParserRejectsGarbage() {
        for (int i = 0; i < 500; i++) {
            byte[] input = new byte[random.nextInt(300)];
            random.nextBytes(input);
            try {
                Envelope env = Envelope.fromBytes(input);
                // If it parses, it must still be structurally sound.
                assertNotNull(env.messageId());
            } catch (PolyMailException expected) {
            }
        }
    }

    @Test
    void envelopeParserRejectsTruncations() {
        Envelope valid = new Envelope(ProtocolConstants.PROTOCOL_VERSION, "dev", "m1", new byte[32],
                new byte[16], new byte[8], 1, 1, 1000L, 2000L, new byte[64]);
        byte[] wire = valid.toBytes();
        for (int cut = 0; cut < wire.length; cut += Math.max(1, wire.length / 50)) {
            byte[] truncated = java.util.Arrays.copyOf(wire, cut);
            try {
                Envelope.fromBytes(truncated);
            } catch (PolyMailException expected) {
            }
        }
    }

    @Test
    void messageParserRejectsGarbageAndHugeLengths() {
        for (int i = 0; i < 500; i++) {
            byte[] input = new byte[random.nextInt(200)];
            random.nextBytes(input);
            try {
                GpmpMessage.fromBytes(input);
            } catch (PolyMailException expected) {
            }
        }
        // Declared length far beyond the cap must be rejected, not allocated.
        byte[] evil = new byte[]{(byte) 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(PolyMailException.class, () -> GpmpMessage.fromBytes(evil));
        assertThrows(PolyMailException.class, () -> GpmpMessage.fromBytes(new byte[0]));
    }

    @Test
    void decryptRejectsMalformedCiphertext() {
        KeyPair recipient = crypto.createEncryptionKeyPair();
        for (int i = 0; i < 100; i++) {
            byte[] input = new byte[random.nextInt(200)];
            random.nextBytes(input);
            try {
                crypto.decrypt(input, recipient.getPrivate().getEncoded(), new byte[0]);
                fail("garbage ciphertext must not decrypt");
            } catch (PolyMailException expected) {
            }
        }
    }

    @Test
    void sealOpenRejectsTampering() {
        byte[] key = crypto.randomContentKey();
        byte[] sealed = crypto.seal("data".getBytes(), key);
        sealed[sealed.length - 1] ^= 1;
        assertThrows(PolyMailException.class, () -> crypto.open(sealed, key));
        assertThrows(PolyMailException.class, () -> crypto.open(sealed, crypto.randomContentKey()));
    }

    @Test
    void contentHashIsNot_aSecretHidingPlaintext() {
        // Hashing ciphertext (not plaintext) means identical plaintexts with fresh
        // randomness produce distinct hashes: no guessing oracle.
        KeyPair recipient = crypto.createEncryptionKeyPair();
        byte[] c1 = crypto.encrypt("same".getBytes(), recipient.getPublic().getEncoded(), null);
        byte[] c2 = crypto.encrypt("same".getBytes(), recipient.getPublic().getEncoded(), null);
        assertFalse(java.util.Arrays.equals(
                crypto.contentHash(c1), crypto.contentHash(c2)));
    }
}
