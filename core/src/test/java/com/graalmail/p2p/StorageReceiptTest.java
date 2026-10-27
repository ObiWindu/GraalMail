package com.graalmail.p2p;

import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.mailbox.StorageReceipt;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class StorageReceiptTest {
    private final EncryptionService crypto = new DefaultEncryptionService();

    @Test
    void validReceiptVerifies() {
        KeyPair peerKey = crypto.createSigningKeyPair();
        byte[] sig = crypto.sign(peerKey, StorageReceipt.signingBytes("cid", "msg-1", "peer1", 1000L, 2000L, ProtocolConstants.PROTOCOL_VERSION));
        StorageReceipt r = new StorageReceipt("cid", "msg-1", "peer1", 1000L, 2000L, ProtocolConstants.PROTOCOL_VERSION, sig);
        assertTrue(crypto.verify(peerKey.getPublic().getEncoded(), r.signingBytes(), r.signature()));
    }

    @Test
    void transplantedReceiptDoesNotVerify() {
        KeyPair peerKey = crypto.createSigningKeyPair();
        byte[] sig = crypto.sign(peerKey, StorageReceipt.signingBytes("cid", "msg-1", "peer1", 1000L, 2000L, ProtocolConstants.PROTOCOL_VERSION));
        // Same signature presented for different content: must fail.
        StorageReceipt forged = new StorageReceipt("other-cid", "msg-1", "peer1", 1000L, 2000L, ProtocolConstants.PROTOCOL_VERSION, sig);
        assertFalse(crypto.verify(peerKey.getPublic().getEncoded(), forged.signingBytes(), forged.signature()));
    }

    @Test
    void wrongPeerKeyDoesNotVerify() {
        KeyPair a = crypto.createSigningKeyPair();
        KeyPair b = crypto.createSigningKeyPair();
        byte[] sig = crypto.sign(a, StorageReceipt.signingBytes("cid", "msg-1", "peer1", 1000L, 2000L, ProtocolConstants.PROTOCOL_VERSION));
        StorageReceipt r = new StorageReceipt("cid", "msg-1", "peer1", 1000L, 2000L, ProtocolConstants.PROTOCOL_VERSION, sig);
        assertFalse(crypto.verify(b.getPublic().getEncoded(), r.signingBytes(), r.signature()));
    }
}
