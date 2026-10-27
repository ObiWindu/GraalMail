package com.graalmail.p2p;

import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTagTest {
    private final EncryptionService crypto = new DefaultEncryptionService();

    @Test
    void tagMatchesRecipientAndMessage() {
        KeyPair recipient = crypto.createEncryptionKeyPair();
        byte[] tag = NotificationTag.generate(recipient.getPublic().getEncoded(), "msg-1");
        assertTrue(NotificationTag.matches(recipient.getPublic().getEncoded(), "msg-1", tag));
    }

    @Test
    void tagDoesNotMatchOtherRecipient() {
        KeyPair a = crypto.createEncryptionKeyPair();
        KeyPair b = crypto.createEncryptionKeyPair();
        byte[] tag = NotificationTag.generate(a.getPublic().getEncoded(), "msg-1");
        assertFalse(NotificationTag.matches(b.getPublic().getEncoded(), "msg-1", tag));
    }

    @Test
    void tagDoesNotMatchOtherMessage() {
        KeyPair recipient = crypto.createEncryptionKeyPair();
        byte[] tag = NotificationTag.generate(recipient.getPublic().getEncoded(), "msg-1");
        assertFalse(NotificationTag.matches(recipient.getPublic().getEncoded(), "msg-2", tag));
    }

    @Test
    void tagsUnlinkableAcrossMessages() {
        KeyPair recipient = crypto.createEncryptionKeyPair();
        byte[] t1 = NotificationTag.generate(recipient.getPublic().getEncoded(), "msg-1");
        byte[] t2 = NotificationTag.generate(recipient.getPublic().getEncoded(), "msg-2");
        assertFalse(java.util.Arrays.equals(t1, t2));
    }

    @Test
    void malformedTagNeverMatches() {
        KeyPair recipient = crypto.createEncryptionKeyPair();
        assertFalse(NotificationTag.matches(recipient.getPublic().getEncoded(), "msg-1", null));
        assertFalse(NotificationTag.matches(recipient.getPublic().getEncoded(), "msg-1", new byte[4]));
    }
}
