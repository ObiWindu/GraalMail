/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonKeyUnlockTest {

    private static final char[] PASSWORD = "mailbox-secret".toCharArray();
    private static final byte[] PASSWORD_BYTES = "mailbox-secret".getBytes(StandardCharsets.UTF_8);

    private static ProtonApiClient.PrivateKey apiKey(String id, String armored) {
        return new ProtonApiClient.PrivateKey(id, "fp-" + id, 1, 1, armored, "", "");
    }

    @Test
    void unlocksDirectPassphraseKey() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey(PASSWORD);
        List<ProtonCrypto.UnlockedKey> unlocked = ProtonKeyUnlock.unlock(
                List.of(apiKey("k1", material.armoredSecret())), Map.of(), PASSWORD_BYTES, List.of());
        assertEquals(1, unlocked.size());
        assertEquals(material.publicKey().getKeyID(), unlocked.get(0).publicKey().getKeyID());
    }

    @Test
    void unlocksSaltedKeyWithBcryptPassword() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey(PASSWORD);
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        // Re-encrypt the same key material under the bcrypt-derived passphrase.
        String hashed = new String(ProtonBcrypt.hash(PASSWORD_BYTES, salt), StandardCharsets.UTF_8);
        byte[] derived = hashed.substring(29).getBytes(StandardCharsets.UTF_8);
        org.bouncycastle.openpgp.PGPSecretKey secret = PgpFixtures.parseSecret(material.armoredSecret());
        // Sanity: the derived passphrase differs from the raw password.
        assertFalse(java.util.Arrays.equals(derived, PASSWORD_BYTES));

        PgpFixtures.KeyMaterial salted = rewrap(secret, derived);
        List<ProtonCrypto.UnlockedKey> unlocked = ProtonKeyUnlock.unlock(
                List.of(apiKey("k2", salted.armoredSecret())), Map.of("k2", salt),
                PASSWORD_BYTES, List.of());
        assertEquals(1, unlocked.size());
        assertEquals(material.publicKey().getKeyID(), unlocked.get(0).publicKey().getKeyID());
    }

    @Test
    void wrongPasswordFails() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey(PASSWORD);
        assertThrows(Exception.class, () -> ProtonKeyUnlock.unlock(
                List.of(apiKey("k1", material.armoredSecret())), Map.of(),
                "wrong".getBytes(StandardCharsets.UTF_8), List.of()));
    }

    @Test
    void inactiveAndEmptyKeysAreSkipped() {
        var inactive = new ProtonApiClient.PrivateKey("k9", "fp", 1, 0, "bogus", "", "");
        assertTrue(ProtonKeyUnlock.unlock(List.of(inactive), Map.of(), PASSWORD_BYTES, List.of()).isEmpty());
        assertTrue(ProtonKeyUnlock.unlock(List.of(), Map.of(), PASSWORD_BYTES, List.of()).isEmpty());
    }

    private static PgpFixtures.KeyMaterial rewrap(org.bouncycastle.openpgp.PGPSecretKey secret,
                                                  byte[] passphrase) throws Exception {
        var decryptor = new org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder()
                .setProvider("BC").build("mailbox-secret".toCharArray());
        var privateKey = secret.extractPrivateKey(decryptor);
        var encryptor = new org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder(
                        org.bouncycastle.openpgp.PGPEncryptedData.AES_256)
                .setProvider("BC").build(new String(passphrase, StandardCharsets.UTF_8).toCharArray());
        var calculator = new org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder()
                .build().get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA1);
        var rewrapped = new org.bouncycastle.openpgp.PGPSecretKey(
                privateKey, secret.getPublicKey(), calculator, true, encryptor);
        var armored = new java.io.ByteArrayOutputStream();
        try (var out = new org.bouncycastle.bcpg.ArmoredOutputStream(armored)) {
            rewrapped.encode(out);
        }
        return new PgpFixtures.KeyMaterial(armored.toString(StandardCharsets.UTF_8),
                secret.getPublicKey(), privateKey);
    }
}
