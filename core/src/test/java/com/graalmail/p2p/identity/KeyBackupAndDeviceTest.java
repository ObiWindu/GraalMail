package com.graalmail.p2p.identity;

import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeyBackupAndDeviceTest {
    private final EncryptionService crypto = new DefaultEncryptionService();

    @Test
    void backupRoundTrip() {
        KeyPair signing = crypto.createSigningKeyPair();
        KeyPair encryption = crypto.createEncryptionKeyPair();
        KeyBackupService backup = new KeyBackupService();
        byte[] blob = backup.exportKey(signing, encryption, "correct horse".toCharArray());
        KeyBackupService.RestoredKeys restored = backup.importKey(blob, "correct horse".toCharArray());
        assertArrayEquals(signing.getPrivate().getEncoded(), restored.signingKeyPair().getPrivate().getEncoded());
        assertArrayEquals(encryption.getPrivate().getEncoded(), restored.encryptionKeyPair().getPrivate().getEncoded());
        // Restored keys are usable.
        byte[] sig = crypto.sign(restored.signingKeyPair(), "data".getBytes());
        assertTrue(crypto.verify(restored.signingKeyPair().getPublic().getEncoded(), "data".getBytes(), sig));
    }

    @Test
    void wrongPassphraseFailsClosed() {
        KeyBackupService backup = new KeyBackupService();
        byte[] blob = backup.exportKey(crypto.createSigningKeyPair(), crypto.createEncryptionKeyPair(), "right".toCharArray());
        assertThrows(PolyMailException.class, () -> backup.importKey(blob, "wrong".toCharArray()));
    }

    @Test
    void tamperedBackupFailsClosed() {
        KeyBackupService backup = new KeyBackupService();
        byte[] blob = backup.exportKey(crypto.createSigningKeyPair(), crypto.createEncryptionKeyPair(), "pw".toCharArray());
        blob[blob.length - 1] ^= 1;
        assertThrows(PolyMailException.class, () -> backup.importKey(blob, "pw".toCharArray()));
    }

    @Test
    void deviceAuthorizeAndRevoke() {
        KeyPair owner = crypto.createSigningKeyPair();
        IdentityRecord identity = new IdentityRecord("bob", "0xB", owner.getPublic().getEncoded(),
                crypto.createEncryptionKeyPair().getPublic().getEncoded(), 1, List.of(), List.of(),
                System.currentTimeMillis(), 0, false, new byte[0]);
        DeviceManager devices = new DeviceManager();
        byte[] deviceKey = crypto.createEncryptionKeyPair().getPublic().getEncoded();

        IdentityRecord enrolled = devices.authorizeDevice(identity, "pixel-8", deviceKey, owner);
        assertTrue(devices.isAuthorized(enrolled, "pixel-8"));
        assertTrue(devices.verifyEnrollment(enrolled, owner.getPublic().getEncoded(), crypto));

        IdentityRecord revoked = devices.revokeDevice(enrolled, "pixel-8", owner);
        assertFalse(devices.isAuthorized(revoked, "pixel-8"));
        assertTrue(devices.verifyEnrollment(revoked, owner.getPublic().getEncoded(), crypto));
    }

    @Test
    void forgedEnrollmentRejected() {
        KeyPair owner = crypto.createSigningKeyPair();
        KeyPair attacker = crypto.createSigningKeyPair();
        IdentityRecord identity = new IdentityRecord("bob", "0xB", owner.getPublic().getEncoded(),
                crypto.createEncryptionKeyPair().getPublic().getEncoded(), 1, List.of(), List.of(),
                System.currentTimeMillis(), 0, false, new byte[0]);
        DeviceManager devices = new DeviceManager();
        // Attacker signs the enrollment with their own key: must not verify as the owner.
        IdentityRecord forged = devices.authorizeDevice(identity, "evil", new byte[32], attacker);
        assertFalse(devices.verifyEnrollment(forged, owner.getPublic().getEncoded(), crypto));
    }
}
