package com.graalmail.p2p;

import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PolyMailCryptoTest {
    @Test
    void encryptDecryptRoundTrip() {
        EncryptionService crypto = new DefaultEncryptionService();
        KeyPair recipient = crypto.createEncryptionKeyPair();
        byte[] plaintext = "hello polymail".getBytes();
        byte[] aad = "msg-1".getBytes();
        byte[] cipher = crypto.encrypt(plaintext, recipient.getPublic().getEncoded(), aad);
        byte[] out = crypto.decrypt(cipher, recipient.getPrivate().getEncoded(), aad);
        assertArrayEquals(plaintext, out);
    }

    @Test
    void tamperedCiphertextFails() {
        EncryptionService crypto = new DefaultEncryptionService();
        KeyPair recipient = crypto.createEncryptionKeyPair();
        byte[] plaintext = "hello".getBytes();
        byte[] cipher = crypto.encrypt(plaintext, recipient.getPublic().getEncoded(), new byte[0]);
        cipher[cipher.length - 1] ^= 1;
        assertThrows(Exception.class, () -> crypto.decrypt(cipher, recipient.getPrivate().getEncoded(), new byte[0]));
    }

    @Test
    void signatureVerifies() {
        EncryptionService crypto = new DefaultEncryptionService();
        KeyPair signing = crypto.createSigningKeyPair();
        byte[] data = "data".getBytes();
        byte[] sig = crypto.sign(signing, data);
        assertTrue(crypto.verify(signing.getPublic().getEncoded(), data, sig));
        assertFalse(crypto.verify(signing.getPublic().getEncoded(), "other".getBytes(), sig));
    }

    @Test
    void envelopeHashDeterministic() {
        Envelope e = new Envelope("GPMP/1", "dev", "m1", null, "ct".getBytes(), "aad".getBytes(), 1, 2, 10, 20, null);
        byte[] h1 = new DefaultEncryptionService().contentHash(e.unsignedContentBytes());
        byte[] h2 = new DefaultEncryptionService().contentHash(e.unsignedContentBytes());
        assertArrayEquals(h1, h2);
    }

    @Test
    void envelopeRoundTripSerialization() {
        byte[] hash = new byte[32];
        Envelope e = new Envelope("GPMP/1", "dev", "m1", hash, "ct".getBytes(), "aad".getBytes(), 1, 2, 10, 20, "sig".getBytes());
        Envelope e2 = Envelope.fromBytes(e.toBytes());
        assertEquals(e.messageId(), e2.messageId());
        assertArrayEquals(e.ciphertext(), e2.ciphertext());
        assertArrayEquals(e.signature(), e2.signature());
    }
}
