/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;

/**
 * Decrypts armored PGP message bodies with unlocked Proton keys.
 *
 * <p>Unencrypted messages pass through untouched. Integrity-protected messages
 * are verified; a failed check is reported, never ignored.</p>
 */
public final class ProtonMessageCrypto {

    private ProtonMessageCrypto() {
    }

    /**
     * Decrypts an armored PGP message.
     *
     * @param armored the ASCII-armored {@code PGP MESSAGE} block
     * @param keys unlocked recipient keys, tried in order
     * @return the decrypted literal bytes (typically MIME)
     */
    public static byte[] decryptArmored(String armored, List<ProtonCrypto.UnlockedKey> keys) {
        ProtonCrypto.ensureRegistered();
        if (armored == null || armored.isBlank()) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "Proton answered with an empty message body.", false);
        }
        if (keys == null || keys.isEmpty()) {
            throw new ProviderException(ErrorCode.AUTHENTICATION_FAILED,
                    "No unlocked Proton key can read this message.", false);
        }
        try (InputStream decoder = PGPUtil.getDecoderStream(
                new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII)))) {
            PGPObjectFactory factory = new PGPObjectFactory(decoder, new JcaKeyFingerprintCalculator());
            PGPEncryptedDataList encrypted = null;
            Object next;
            while ((next = factory.nextObject()) != null) {
                if (next instanceof PGPEncryptedDataList list) {
                    encrypted = list;
                    break;
                }
            }
            if (encrypted == null) {
                throw new IllegalStateException("No encrypted data in Proton message");
            }
            Exception lastFailure = null;
            for (Object packet : encrypted) {
                if (!(packet instanceof PGPPublicKeyEncryptedData keyed)) {
                    continue;
                }
                for (ProtonCrypto.UnlockedKey key : keys) {
                    if (key.publicKey().getKeyID() != keyed.getKeyID()) {
                        continue;
                    }
                    try (InputStream clear = keyed.getDataStream(ProtonKeyUnlock.decryptorFor(key.privateKey()))) {
                        byte[] literal = ProtonKeyUnlock.literalBytes(clear);
                        if (keyed.isIntegrityProtected() && !keyed.verify()) {
                            throw new IllegalStateException(
                                    "Proton message failed its integrity check");
                        }
                        return literal;
                    } catch (PGPException e) {
                        lastFailure = e;
                    }
                }
            }
            throw new IllegalStateException("No unlocked Proton key matches this message",
                    lastFailure);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException(ErrorCode.PROTOCOL_ERROR,
                    "A Proton message could not be decrypted: " + e.getMessage(), false, e);
        }
    }
}
