/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.crypto.KeyReference;
import com.graalmail.p2p.crypto.SecureKeyStore;
import com.graalmail.security.store.AesGcmProtector;
import com.graalmail.security.store.SecretProtector;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Desktop {@link SecureKeyStore} (spec section 3, desktop key storage).
 *
 * <p>X25519/Ed25519 keys are generated in memory, then sealed at rest with the
 * same passphrase-protected AES-GCM protector as the credential store (Secret
 * Service on Linux where available, encrypted file fallback). Private key bytes
 * only exist unsealed while an operation runs. Like the credential store, the
 * passphrase file is a single-user convenience, not a hardware boundary: users
 * needing one should set {@code GRAALMAIL_PASSPHRASE}.
 */
public final class DesktopSecureKeyStore implements SecureKeyStore {
    private final Path keyDirectory;
    private final SecretProtector protector;
    private final EncryptionService crypto = new DefaultEncryptionService();

    private DesktopSecureKeyStore(Path keyDirectory, SecretProtector protector) {
        this.keyDirectory = keyDirectory;
        this.protector = protector;
    }

    public static DesktopSecureKeyStore open(AppPaths paths) throws Exception {
        Path dir = paths.baseDirectory().resolve("polymail-keys");
        Files.createDirectories(dir);
        byte[] salt = DesktopSecretStore.readOrCreateSalt(paths.saltFile());
        char[] passphrase = DesktopSecretStore.resolvePassphrase(paths);
        try {
            return new DesktopSecureKeyStore(dir, new AesGcmProtector(passphrase, salt));
        } finally {
            java.util.Arrays.fill(passphrase, '\0');
        }
    }

    @Override
    public KeyReference createEncryptionKey() {
        return store("encryption", crypto.createEncryptionKeyPair(), "X25519");
    }

    @Override
    public KeyReference createSigningKey() {
        return store("signing", crypto.createSigningKeyPair(), "Ed25519");
    }

    @Override
    public byte[] sign(KeyReference key, byte[] data) {
        KeyPair kp = load(key, "Ed25519");
        return crypto.sign(kp, data);
    }

    @Override
    public byte[] decrypt(KeyReference key, byte[] ciphertext) {
        KeyPair kp = load(key, "X25519");
        return crypto.decrypt(ciphertext, kp.getPrivate().getEncoded(), new byte[0]);
    }

    private KeyReference store(String type, KeyPair pair, String algorithm) {
        try {
            KeyReference ref = new KeyReference(java.util.UUID.randomUUID().toString(), type);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(baos)) {
                byte[] priv = pair.getPrivate().getEncoded();
                byte[] pub = pair.getPublic().getEncoded();
                out.writeInt(priv.length); out.write(priv);
                out.writeInt(pub.length); out.write(pub);
                out.flush();
                byte[] sealed = protector.encrypt(baos.toByteArray());
                Path file = keyDirectory.resolve(ref.id() + ".key");
                Files.write(file, sealed);
                try {
                    Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                } catch (Exception ignored) {}
            }
            return ref;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.STORAGE_ERROR, "cannot store key", e);
        }
    }

    private KeyPair load(KeyReference key, String algorithm) {
        try {
            Path file = keyDirectory.resolve(key.id() + ".key");
            if (!Files.exists(file)) {
                throw new PolyMailException(ErrorCode.KEY_UNTRUSTED, "unknown key");
            }
            byte[] sealed = Files.readAllBytes(file);
            byte[] raw = protector.decrypt(sealed);
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
                int privLen = in.readInt();
                byte[] priv = new byte[privLen]; in.readFully(priv);
                int pubLen = in.readInt();
                byte[] pub = new byte[pubLen]; in.readFully(pub);
                KeyFactory factory = KeyFactory.getInstance(algorithm);
                return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(pub)),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(priv)));
            }
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "cannot load key", e);
        }
    }
}
