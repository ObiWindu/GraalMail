/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;

/** Builds throwaway PGP keys and messages for offline crypto tests. */
final class PgpFixtures {

    record KeyMaterial(String armoredSecret, PGPPublicKey publicKey, PGPPrivateKey unlocked) {
    }

    private PgpFixtures() {
    }

    static {
        ProtonCrypto.ensureRegistered();
    }

    static KeyMaterial generateKey(char[] passphrase) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair pair = generator.generateKeyPair();
        PGPKeyPair keyPair = new org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair(
                PGPPublicKey.RSA_GENERAL, pair, new Date());
        PGPDigestCalculator calculator = new JcaPGPDigestCalculatorProviderBuilder()
                .build().get(HashAlgorithmTags.SHA1);
        var encryptor = new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256)
                .setProvider("BC").build(passphrase);
        java.security.interfaces.RSAPrivateCrtKey crt =
                (java.security.interfaces.RSAPrivateCrtKey) pair.getPrivate();
        var secretData = new org.bouncycastle.bcpg.RSASecretBCPGKey(
                crt.getPrivateExponent(), crt.getPrimeP(), crt.getPrimeQ());
        PGPPrivateKey privateKey = new org.bouncycastle.openpgp.PGPPrivateKey(
                keyPair.getKeyID(), keyPair.getPublicKey().getPublicKeyPacket(), secretData);
        PGPSecretKey secret = new PGPSecretKey(privateKey, keyPair.getPublicKey(),
                calculator, true, encryptor);
        ByteArrayOutputStream armored = new ByteArrayOutputStream();
        try (ArmoredOutputStream out = new ArmoredOutputStream(armored)) {
            out.setHeader("Version", "Test");
            secret.encode(out);
        }
        PGPPrivateKey unlocked = secret.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passphrase));
        return new KeyMaterial(armored.toString(StandardCharsets.UTF_8),
                secret.getPublicKey(), unlocked);
    }

    static PGPPublicKeyRingCollection ringOf(PGPPublicKey key) throws Exception {
        return new PGPPublicKeyRingCollection(
                java.util.List.of(new PGPPublicKeyRing(key.getEncoded(),
                        new org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator())));
    }

    static String clearsign(String text, PGPSecretKey secret, char[] passphrase) throws Exception {
        PGPPrivateKey unlocked = secret.extractPrivateKey(
                new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passphrase));
        PGPSignatureGenerator generator = new PGPSignatureGenerator(
                new JcaPGPContentSignerBuilder(secret.getPublicKey().getAlgorithm(),
                        HashAlgorithmTags.SHA256).setProvider("BC"));
        generator.init(PGPSignature.CANONICAL_TEXT_DOCUMENT, unlocked);
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (String line : lines) {
            generator.update((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        PGPSignature signature = generator.generate();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        signature.encode(encoded);
        String base64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded.toByteArray());
        StringBuilder out = new StringBuilder("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n");
        out.append(text).append('\n');
        out.append("-----BEGIN PGP SIGNATURE-----\nVersion: Test\n\n");
        out.append(base64).append('\n');
        out.append("-----END PGP SIGNATURE-----\n");
        return out.toString();
    }

    static PGPSecretKey parseSecret(String armored) throws Exception {
        try (var in = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(
                new java.io.ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII)))) {
            var factory = new org.bouncycastle.openpgp.PGPObjectFactory(in,
                    new org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator());
            Object next = factory.nextObject();
            if (next instanceof PGPSecretKey secret) {
                return secret;
            }
            if (next instanceof org.bouncycastle.openpgp.PGPSecretKeyRing ring) {
                return ring.getSecretKey();
            }
            throw new IllegalStateException("No secret key in test fixture");
        }
    }

    static String encrypt(PGPPublicKey recipient, byte[] plaintext, boolean compress) throws Exception {
        ByteArrayOutputStream armored = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = new ArmoredOutputStream(armored)) {
            armor.setHeader("Version", "Test");
            var dataEncryptor = new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                    .setProvider("BC").setWithIntegrityPacket(true);
            var encrypted = new PGPEncryptedDataGenerator(dataEncryptor);
            encrypted.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(recipient).setProvider("BC"));
            try (var encryptedOut = encrypted.open(armor, new byte[1 << 16])) {
                var payload = encryptedOut;
                PGPCompressedDataGenerator zip = null;
                if (compress) {
                    zip = new PGPCompressedDataGenerator(PGPCompressedData.ZIP);
                    payload = zip.open(encryptedOut);
                }
                var literal = new PGPLiteralDataGenerator().open(payload,
                        PGPLiteralData.BINARY, "message", new Date(), new byte[1 << 16]);
                literal.write(plaintext);
                literal.close();
                if (zip != null) {
                    zip.close();
                }
            }
        }
        return armored.toString(StandardCharsets.UTF_8);
    }
}
