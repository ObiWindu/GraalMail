/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;

/**
 * Unlocks Proton user and address keys and decrypts PGP message bodies.
 *
 * <p>Keys arrive armored from {@code /users} and {@code /addresses}; each
 * private packet is encrypted with a passphrase derived from the mailbox
 * password (directly, or via the per-key salt from {@code /keys/salts}, or
 * via an encrypted token unlocked by a parent key). Message bodies are
 * decrypted with the unlocked address keys and parsed as MIME by the caller.</p>
 */
public final class ProtonKeyUnlock {

    private ProtonKeyUnlock() {
    }

    /**
     * Unlocks every active key in the list.
     *
     * @param keys user or address keys from the API
     * @param salts key salts by key id, may be empty
     * @param mailboxPassword mailbox password bytes (UTF-8)
     * @param parents already unlocked parent keys, for token-encrypted keys
     * @return the unlocked keys; empty when none could be unlocked
     */
    public static List<ProtonCrypto.UnlockedKey> unlock(
            List<ProtonApiClient.PrivateKey> keys, Map<String, byte[]> salts,
            byte[] mailboxPassword, List<ProtonCrypto.UnlockedKey> parents) {
        ProtonCrypto.ensureRegistered();
        List<ProtonCrypto.UnlockedKey> unlocked = new ArrayList<>();
        for (ProtonApiClient.PrivateKey key : keys) {
            if (key.Active() != 1) {
                continue;
            }
            try {
                byte[] passphrase = passphraseFor(key, salts, mailboxPassword, parents);
                unlocked.addAll(unlockPackets(key, passphrase));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                        "A Proton key could not be unlocked. The mailbox password may be wrong.",
                        false, e);
            }
        }
        return unlocked;
    }

    private static byte[] passphraseFor(ProtonApiClient.PrivateKey key, Map<String, byte[]> salts,
                                        byte[] mailboxPassword,
                                        List<ProtonCrypto.UnlockedKey> parents) throws Exception {
        if (!key.Token().isBlank()) {
            if (parents == null || parents.isEmpty()) {
                throw new IllegalStateException("Token-encrypted key without a parent key");
            }
            return ProtonMessageCrypto.decryptArmored(key.Token(), parents);
        }
        byte[] salt = salts == null ? null : salts.get(key.ID());
        if (salt != null && salt.length > 0) {
            String hashed = new String(ProtonBcrypt.hash(mailboxPassword, salt), StandardCharsets.UTF_8);
            if (hashed.length() <= 29) {
                throw new IllegalStateException("Invalid Proton key password hash");
            }
            return hashed.substring(29).getBytes(StandardCharsets.UTF_8);
        }
        return mailboxPassword;
    }

    private static List<ProtonCrypto.UnlockedKey> unlockPackets(
            ProtonApiClient.PrivateKey key, byte[] passphrase) throws Exception {
        byte[] armored = key.PrivateKey().getBytes(StandardCharsets.US_ASCII);
        List<ProtonCrypto.UnlockedKey> result = new ArrayList<>();
        try (InputStream decoder = PGPUtil.getDecoderStream(new ByteArrayInputStream(armored))) {
            PGPSecretKeyRingCollection rings = new PGPSecretKeyRingCollection(
                    decoder, new JcaKeyFingerprintCalculator());
            for (PGPSecretKeyRing ring : rings) {
                for (PGPSecretKey secret : ring) {
                    PGPPrivateKey privateKey = secret.extractPrivateKey(
                            new JcePBESecretKeyDecryptorBuilder()
                                    .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
                                    .build(toChars(passphrase)));
                    result.add(new ProtonCrypto.UnlockedKey(secret.getPublicKey(), privateKey));
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("No key packets in Proton key " + key.ID());
        }
        return result;
    }

    private static char[] toChars(byte[] value) {
        return new String(value, StandardCharsets.UTF_8).toCharArray();
    }

    /** Decodes base64 key-salt payloads from {@code /keys/salts}. */
    public static java.util.Map<String, byte[]> decodeSalts(List<ProtonApiClient.KeySalt> salts) {
        Map<String, byte[]> decoded = new java.util.LinkedHashMap<>();
        for (ProtonApiClient.KeySalt salt : salts) {
            if (salt.KeySalt() == null || salt.KeySalt().isBlank()) {
                continue;
            }
            try {
                decoded.put(salt.ID(), Base64.getDecoder().decode(salt.KeySalt()));
            } catch (IllegalArgumentException ignored) {
                // skip malformed salts
            }
        }
        return decoded;
    }

    static PublicKeyDataDecryptorFactory decryptorFor(PGPPrivateKey key) {
        return new JcePublicKeyDataDecryptorFactoryBuilder()
                .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
                .build(key);
    }

    /** Reads decrypted literal data from a PGP message stream. */
    static byte[] literalBytes(InputStream clear) throws Exception {
        PGPObjectFactory factory = new PGPObjectFactory(clear, new JcaKeyFingerprintCalculator());
        Object next = factory.nextObject();
        if (next instanceof PGPCompressedData compressed) {
            factory = new PGPObjectFactory(
                    compressed.getDataStream(), new JcaKeyFingerprintCalculator());
            next = factory.nextObject();
        }
        if (next instanceof PGPLiteralData literal) {
            try (InputStream in = literal.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toByteArray();
            }
        }
        throw new IllegalStateException("Proton message has no readable content");
    }
}
