/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

/**
 * Proton's SRP-6a login proofs (2048 bit, SHA-512 expansion, bcrypt password
 * hashing), following the open Proton WebClient algorithm.
 *
 * <p>All multi-precision integers cross the wire little-endian. The server
 * modulus arrives as a PGP clear-signed message whose signature is verified
 * against Proton's hardcoded modulus key before use, so a rogue endpoint
 * cannot downgrade the group.</p>
 */
public final class ProtonSrp {

    /** Proton's public key that signs the SRP modulus. */
    static final String MODULUS_PUBLIC_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            xjMEXAHLgxYJKwYBBAHaRw8BAQdAFurWXXwjTemqjD7CXjXVyKf0of7n9Ctm
            L8v9enkzggHNEnByb3RvbkBzcnAubW9kdWx1c8J3BBAWCgApBQJcAcuDBgsJ
            BwgDAgkQNQWFxOlRjyYEFQgKAgMWAgECGQECGwMCHgEAAPGRAP9sauJsW12U
            MnTQUZpsbJb53d0Wv55mZIIiJL2XulpWPQD/V6NglBd96lZKBmInSXX/kXat
            Sv+y0io+LR8i2+jV+AbOOARcAcuDEgorBgEEAZdVAQUBAQdAeJHUz1c9+KfE
            kSIgcBRE3WuXC4oj5a2/U3oASExGDW4DAQgHwmEEGBYIABMFAlwBy4MJEDUF
            hcTpUY8mAhsMAAD/XQD8DxNI6E78meodQI+wLsrKLeHn32iLvUqJbVDhfWSU
            WO4BAMcm1u02t4VKw++ttECPt+HUgPUq5pqQWe5Q2cW4TMsE
            =Y4Mw
            -----END PGP PUBLIC KEY BLOCK-----""";

    private static final int DEFAULT_MODULUS_BITS = 2048;
    private static final BigInteger GENERATOR = BigInteger.valueOf(2);
    private static final BigInteger ONE = BigInteger.ONE;

    private final SecureRandom random;
    private final int modulusBits;
    private final int modulusBytes;
    private final ProofComputer proofComputer;

    /** Computes proofs; injectable for tests. */
    interface ProofComputer {
        Proofs compute(ProtonSrp srp, byte[] password, int version, byte[] salt,
                       byte[] serverEphemeral, String signedModulus) throws Exception;
    }

    public ProtonSrp() {
        this(new SecureRandom(), DEFAULT_MODULUS_BITS, ProtonSrp::defaultProofs);
    }

    ProtonSrp(SecureRandom random) {
        this(random, DEFAULT_MODULUS_BITS, ProtonSrp::defaultProofs);
    }

    ProtonSrp(SecureRandom random, int modulusBits) {
        this(random, modulusBits, ProtonSrp::defaultProofs);
    }

    ProtonSrp(ProofComputer proofComputer) {
        this(new SecureRandom(), DEFAULT_MODULUS_BITS, proofComputer);
    }

    private ProtonSrp(SecureRandom random, int modulusBits, ProofComputer proofComputer) {
        this.random = random;
        this.modulusBits = modulusBits;
        this.modulusBytes = (modulusBits + 7) / 8;
        this.proofComputer = proofComputer;
    }

    /** Client proofs sent to {@code POST /auth}. */
    public record Proofs(byte[] clientEphemeral, byte[] clientProof, byte[] expectedServerProof) {
    }

    /**
     * Computes login proofs for the given password and server values.
     *
     * @param password login password bytes (UTF-8)
     * @param version auth version from {@code /auth/info} (3 or 4)
     * @param saltRaw raw salt bytes (base64-decoded {@code Salt} field)
     * @param serverEphemeralRaw raw server ephemeral (base64-decoded)
     * @param signedModulus the clear-signed modulus from {@code /auth/info}
     */
    public Proofs proofs(byte[] password, int version, byte[] saltRaw,
                         byte[] serverEphemeralRaw, String signedModulus) throws Exception {
        return proofComputer.compute(this, password, version, saltRaw, serverEphemeralRaw, signedModulus);
    }

    private static Proofs defaultProofs(ProtonSrp srp, byte[] password, int version, byte[] saltRaw,
                                        byte[] serverEphemeralRaw, String signedModulus) throws Exception {
        byte[] modulusRaw = decodeModulus(signedModulus);
        byte[] hashed = hashPassword(version, password, saltRaw, modulusRaw);
        return srp.generateProofs(modulusRaw, hashed, serverEphemeralRaw);
    }

    /** Verifies the server proof returned by {@code POST /auth}. */
    public static void verifyServerProof(byte[] expected, String serverProofBase64) {
        byte[] actual;
        try {
            actual = Base64.getDecoder().decode(serverProofBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed SRP server proof");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalStateException("Invalid SRP server proof");
        }
    }

    /**
     * Decodes the SRP modulus after verifying its PGP signature against
     * Proton's modulus key.
     */
    public static byte[] decodeModulus(String signedMessage) throws Exception {
        ProtonCrypto.ensureRegistered();
        PGPPublicKeyRingCollection keys = modulusKeys();
        return decodeModulus(signedMessage, keys);
    }

    static byte[] decodeModulus(String signedMessage, PGPPublicKeyRingCollection trustedKeys)
            throws Exception {
        ProtonCrypto.ensureRegistered();
        ClearSigned signed = ClearSigned.parse(signedMessage);
        byte[] signatureBytes = decodeArmor(signed.armor());
        PGPSignature signature;
        try (var in = new org.bouncycastle.bcpg.BCPGInputStream(new ByteArrayInputStream(signatureBytes))) {
            PGPObjectFactory factory = new PGPObjectFactory(in, new JcaKeyFingerprintCalculator());
            Object first;
            try {
                first = factory.nextObject();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Malformed SRP modulus signature", e);
            }
            if (!(first instanceof PGPSignatureList list) || list.isEmpty()) {
                throw new IllegalStateException("Malformed SRP modulus signature");
            }
            signature = list.get(0);
        }
        PGPPublicKey key = trustedKeys.getPublicKey(signature.getKeyID());
        if (key == null) {
            throw new IllegalStateException("SRP modulus signed by an unknown key");
        }
        if (key.getAlgorithm() == org.bouncycastle.bcpg.PublicKeyAlgorithmTags.EDDSA) {
            verifyEddsa(signature, key, signed.textLines());
        } else {
            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider("BC"), key);
            boolean textMode = signature.getSignatureType() == PGPSignature.CANONICAL_TEXT_DOCUMENT;
            for (String line : signed.textLines()) {
                byte[] data = textMode
                        ? (line + "\r\n").getBytes(StandardCharsets.UTF_8)
                        : line.getBytes(StandardCharsets.UTF_8);
                signature.update(data);
            }
            if (!signature.verify()) {
                throw new IllegalStateException("SRP modulus signature verification failed");
            }
        }
        String payload = String.join("", signed.textLines()).strip();
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed SRP modulus", e);
        }
    }

    /**
     * Verifies an EdDSA clear-signed message the GPG-compatible way.
     *
     * <p>Proton's signer (and GPG) hash the document with the signature
     * packet's hash algorithm and Ed25519-sign the digest — they do not sign
     * the raw document. The document is the dash-unescaped text lines joined
     * by CRLF without a trailing terminator, followed by the standard v4
     * signature trailer. This exact construction was verified byte-identical
     * against GPG's own verification of a live Proton modulus.</p>
     */
    private static void verifyEddsa(PGPSignature signature, PGPPublicKey key,
                                    List<String> textLines) throws Exception {
        String digestName = switch (signature.getHashAlgorithm()) {
            case org.bouncycastle.bcpg.HashAlgorithmTags.SHA224 -> "SHA-224";
            case org.bouncycastle.bcpg.HashAlgorithmTags.SHA256 -> "SHA-256";
            case org.bouncycastle.bcpg.HashAlgorithmTags.SHA384 -> "SHA-384";
            case org.bouncycastle.bcpg.HashAlgorithmTags.SHA512 -> "SHA-512";
            default -> throw new IllegalStateException("Unsupported EdDSA hash algorithm: "
                    + signature.getHashAlgorithm());
        };
        MessageDigest digest = MessageDigest.getInstance(digestName);
        byte[] lineBreak = {'\r', '\n'};
        for (int i = 0; i < textLines.size(); i++) {
            if (i > 0) {
                digest.update(lineBreak);
            }
            digest.update(textLines.get(i).getBytes(StandardCharsets.UTF_8));
        }
        digest.update(signature.getSignatureTrailer());
        byte[] signedHash = digest.digest();
        byte[] proof = signature.getSignature();
        if (proof.length != 64) {
            throw new IllegalStateException("Malformed EdDSA signature");
        }
        java.security.PublicKey verifier;
        try {
            verifier = new org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter()
                    .setProvider("BC").getPublicKey(key);
        } catch (Exception e) {
            throw new IllegalStateException("Unsupported EdDSA key", e);
        }
        if (!"Ed25519".equalsIgnoreCase(verifier.getAlgorithm())) {
            throw new IllegalStateException("Unsupported EdDSA curve");
        }
        java.security.Signature verify =
                java.security.Signature.getInstance("Ed25519");
        verify.initVerify(verifier);
        verify.update(signedHash);
        if (!verify.verify(proof)) {
            throw new IllegalStateException("SRP modulus signature verification failed");
        }
    }

    static PGPPublicKeyRingCollection modulusKeys() throws Exception {        try (var in = new ByteArrayInputStream(MODULUS_PUBLIC_KEY.getBytes(StandardCharsets.US_ASCII))) {
            try (var decoder = PGPUtil.getDecoderStream(in)) {
                PGPObjectFactory factory = new PGPObjectFactory(decoder, new JcaKeyFingerprintCalculator());
                List<PGPPublicKeyRing> rings = new ArrayList<>();
                Object next;
                while ((next = factory.nextObject()) != null) {
                    if (next instanceof PGPPublicKeyRing ring) {
                        rings.add(ring);
                    }
                }
                return new PGPPublicKeyRingCollection(rings);
            }
        }
    }

    private static byte[] decodeArmor(List<String> armorLines) {
        StringBuilder encoded = new StringBuilder();
        boolean body = false;
        for (String line : armorLines) {
            if (!body) {
                if (line.isBlank()) {
                    body = true;
                }
                continue;
            }
            if (line.startsWith("=")) {
                break;
            }
            encoded.append(line.strip());
        }
        return Base64.getDecoder().decode(encoded.toString());
    }

    /** Parsed PGP clear-signed message: dash-unescaped text plus armor lines. */
    record ClearSigned(List<String> textLines, List<String> armor) {

        static ClearSigned parse(String message) {
            if (message == null) {
                throw new IllegalStateException("Invalid SRP modulus signed PGP block");
            }
            String normalized = message.replace("\r\n", "\n").replace('\r', '\n');
            List<String> lines = Arrays.asList(normalized.split("\n", -1));
            int hashHeader = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("-----BEGIN PGP SIGNED MESSAGE-----")) {
                    hashHeader = i;
                    break;
                }
            }
            if (hashHeader < 0) {
                throw new IllegalStateException("Invalid SRP modulus signed PGP block");
            }
            int blank = -1;
            for (int i = hashHeader + 1; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) {
                    blank = i;
                    break;
                }
            }
            int sigStart = -1;
            for (int i = blank + 1; i < lines.size(); i++) {
                if (lines.get(i).startsWith("-----BEGIN PGP SIGNATURE-----")) {
                    sigStart = i;
                    break;
                }
            }
            if (blank < 0 || sigStart < 0) {
                throw new IllegalStateException("Invalid SRP modulus signed PGP block");
            }
            List<String> text = new ArrayList<>();
            for (int i = blank + 1; i < sigStart; i++) {
                String line = lines.get(i);
                text.add(line.startsWith("- ") ? line.substring(2) : line);
            }
            List<String> armor = new ArrayList<>();
            for (int i = sigStart + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("-----END PGP SIGNATURE-----")) {
                    break;
                }
                armor.add(line);
            }
            if (text.isEmpty() || armor.isEmpty()) {
                throw new IllegalStateException("Invalid SRP modulus signed PGP block");
            }
            return new ClearSigned(List.copyOf(text), List.copyOf(armor));
        }
    }

    /** Proton password hashing (auth versions 3 and 4): bcrypt then SHA-512 expansion. */
    static byte[] hashPassword(int version, byte[] password, byte[] salt, byte[] modulus) throws Exception {
        return switch (version) {
            case 3, 4 -> {
                byte[] salted = concat(salt, "proton".getBytes(StandardCharsets.UTF_8));
                byte[] hashed = ProtonBcrypt.hash(password, salted);
                yield expandHash(concat(hashed, modulus));
            }
            default -> throw new IllegalStateException("Unsupported auth version: " + version);
        };
    }

    /** Four rounds of SHA-512 with a domain byte, producing 256 bytes. */
    static byte[] expandHash(byte[] input) throws Exception {
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        byte[] expanded = new byte[0];
        for (int i = 0; i < 4; i++) {
            sha512.reset();
            sha512.update(input);
            sha512.update((byte) i);
            expanded = concat(expanded, sha512.digest());
        }
        return expanded;
    }

    Proofs generateProofs(byte[] modulusRaw, byte[] hashedBytes, byte[] serverEphemeralBytes)
            throws Exception {
        BigInteger modulus = fromLittleEndian(modulusRaw);
        BigInteger hashed = fromLittleEndian(hashedBytes);
        BigInteger serverEphemeral = fromLittleEndian(serverEphemeralBytes);
        if (modulus.bitLength() != modulusBits) {
            throw new IllegalStateException("SRP modulus has incorrect size");
        }
        BigInteger modulusMinusOne = modulus.subtract(ONE);
        BigInteger multiplier = fromLittleEndian(expandHash(
                concat(toLittleEndian(GENERATOR, modulusBytes), modulusRaw))).mod(modulus);
        checkBounds("SRP multiplier", multiplier, modulus, modulusMinusOne);
        checkBounds("SRP generator", GENERATOR, modulus, modulusMinusOne);
        checkBounds("SRP server ephemeral", serverEphemeral, modulus, modulusMinusOne);

        BigInteger clientSecret;
        BigInteger clientEphemeral;
        BigInteger scrambling;
        while (true) {
            do {
                clientSecret = new BigInteger(modulus.bitLength(), random).mod(modulusMinusOne);
            } while (clientSecret.compareTo(BigInteger.valueOf((long) modulusBits * 2)) <= 0);
            clientEphemeral = GENERATOR.modPow(clientSecret, modulus);
            scrambling = fromLittleEndian(expandHash(concat(
                    toLittleEndian(clientEphemeral, modulusBytes),
                    toLittleEndian(serverEphemeral, modulusBytes))));
            if (!scrambling.equals(BigInteger.ZERO)) {
                break;
            }
        }
        BigInteger subtracted = serverEphemeral.subtract(
                GENERATOR.modPow(hashed, modulus).multiply(multiplier).mod(modulus));
        if (subtracted.signum() < 0) {
            subtracted = subtracted.add(modulus);
        }
        BigInteger exponent = scrambling.multiply(hashed).add(clientSecret).mod(modulusMinusOne);
        BigInteger shared = subtracted.modPow(exponent, modulus);

        byte[] clientProof = expandHash(concat(
                toLittleEndian(clientEphemeral, modulusBytes),
                toLittleEndian(serverEphemeral, modulusBytes),
                toLittleEndian(shared, modulusBytes)));
        byte[] serverProof = expandHash(concat(
                toLittleEndian(clientEphemeral, modulusBytes),
                clientProof,
                toLittleEndian(shared, modulusBytes)));
        return new Proofs(
                toLittleEndian(clientEphemeral, modulusBytes), clientProof, serverProof);
    }

    private static void checkBounds(String what, BigInteger value,
                                    BigInteger modulus, BigInteger modulusMinusOne) {
        if (value.compareTo(ONE) <= 0 || value.compareTo(modulusMinusOne) >= 0
                || value.compareTo(modulus) >= 0) {
            throw new IllegalStateException(what + " is out of bounds");
        }
    }

    /** Little-endian unsigned encoding, zero-padded to the given length. */
    static byte[] toLittleEndian(BigInteger value, int length) {
        byte[] big = value.toByteArray();
        int start = 0;
        while (start < big.length - 1 && big[start] == 0) {
            start++;
        }
        int size = big.length - start;
        if (size > length) {
            throw new IllegalStateException("Integer does not fit in " + length + " bytes");
        }
        byte[] little = new byte[length];
        for (int i = 0; i < size; i++) {
            little[i] = big[big.length - 1 - i];
        }
        return little;
    }

    /** Parses little-endian unsigned bytes. */
    static BigInteger fromLittleEndian(byte[] little) {
        byte[] big = new byte[little.length];
        for (int i = 0; i < little.length; i++) {
            big[i] = little[little.length - 1 - i];
        }
        return new BigInteger(1, big);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }
}
