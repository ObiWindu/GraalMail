package com.graalmail.p2p.identity;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PolyMailException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Multi-device enrollment (spec section 4.4). Each device holds its own mailbox
 * authorization key; the owner authorizes a device by signing a device descriptor
 * into the identity record, and revokes it the same way. Losing the wallet must
 * never silently publish replacement keys: every enrollment change is signed by
 * the owner's current signing key.
 */
public class DeviceManager {
    /** Canonical bytes covered by the owner's enrollment signature. */
    public static byte[] enrollmentBytes(IdentityRecord identity, List<String> authorizedDevices) {
        String s = identity.username() + "|" + identity.walletAddress() + "|" + identity.keyVersion()
                + "|" + String.join(",", authorizedDevices);
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public IdentityRecord authorizeDevice(IdentityRecord identity, String deviceId, byte[] devicePublicKey,
                                          KeyPair ownerSigningKey) {
        if (deviceId == null || deviceId.isBlank() || devicePublicKey == null) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "device descriptor required");
        }
        List<String> devices = new ArrayList<>(identity.authorizedDevices());
        String descriptor = deviceId + ":" + HexFormat.of().formatHex(devicePublicKey);
        devices.removeIf(d -> d.startsWith(deviceId + ":"));
        devices.add(descriptor);
        return withDevices(identity, devices, ownerSigningKey);
    }

    public IdentityRecord revokeDevice(IdentityRecord identity, String deviceId, KeyPair ownerSigningKey) {
        List<String> devices = new ArrayList<>(identity.authorizedDevices());
        devices.removeIf(d -> d.startsWith(deviceId + ":"));
        return withDevices(identity, devices, ownerSigningKey);
    }

    public boolean isAuthorized(IdentityRecord identity, String deviceId) {
        return identity.authorizedDevices().stream().anyMatch(d -> d.startsWith(deviceId + ":"));
    }

    public boolean verifyEnrollment(IdentityRecord identity, byte[] ownerSigningPublicKey,
                                    com.graalmail.p2p.crypto.EncryptionService crypto) {
        if (identity.signature() == null) return false;
        return crypto.verify(ownerSigningPublicKey,
                enrollmentBytes(identity, identity.authorizedDevices()), identity.signature());
    }

    private IdentityRecord withDevices(IdentityRecord identity, List<String> devices, KeyPair ownerSigningKey) {
        try {
            java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initSign(ownerSigningKey.getPrivate());
            byte[] bytes = enrollmentBytes(identity, devices);
            s.update(bytes);
            byte[] sig = s.sign();
            return new IdentityRecord(identity.username(), identity.walletAddress(),
                    identity.signingPublicKey(), identity.encryptionPublicKey(), identity.keyVersion(),
                    List.copyOf(devices), identity.mailboxEndpoints(), identity.createdAt(),
                    identity.expiresAt(), identity.revoked(), sig);
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "enrollment signing failed", e);
        }
    }
}
