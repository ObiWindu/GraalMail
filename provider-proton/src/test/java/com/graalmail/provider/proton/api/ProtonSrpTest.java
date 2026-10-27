/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the SRP building blocks and a full client/server handshake.
 *
 * <p>The handshake uses the Mersenne prime 2^1279-1 as the group so the test
 * runs without a live server; the production group stays 2048 bit.</p>
 */
class ProtonSrpTest {

    private static final BigInteger GROUP = BigInteger.TWO.pow(1279).subtract(BigInteger.ONE);
    private static final int GROUP_BITS = 1279;
    private static final byte[] SALT = "0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PASSWORD = "correct horse".getBytes(StandardCharsets.UTF_8);

    @Test
    void expandHashYields256DeterministicBytes() throws Exception {
        byte[] first = ProtonSrp.expandHash("proton".getBytes(StandardCharsets.UTF_8));
        byte[] second = ProtonSrp.expandHash("proton".getBytes(StandardCharsets.UTF_8));
        assertEquals(256, first.length);
        assertArrayEquals(first, second);
        assertFalse(Arrays.equals(first,
                ProtonSrp.expandHash("proton2".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void littleEndianConversionsRoundTrip() {
        BigInteger value = new BigInteger("123456789012345678901234567890");
        byte[] little = ProtonSrp.toLittleEndian(value, 32);
        assertEquals(32, little.length);
        assertEquals(value, ProtonSrp.fromLittleEndian(little));
        assertArrayEquals(new byte[8], ProtonSrp.toLittleEndian(BigInteger.ZERO, 8));
        assertThrows(IllegalStateException.class,
                () -> ProtonSrp.toLittleEndian(BigInteger.valueOf(256), 1));
    }

    @Test
    void bcryptHashVerifiesAndHasProtonFormat() {
        byte[] salted = ProtonSrp.concat(SALT,
                "proton".getBytes(StandardCharsets.UTF_8));
        byte[] hashed = ProtonBcrypt.hash(PASSWORD, salted);
        String text = new String(hashed, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("$2y$10$"), text);
        assertEquals(60, text.length());
        assertArrayEquals(hashed, ProtonBcrypt.hash(PASSWORD, salted));
        assertTrue(OpenBSDBCrypt.checkPassword("$2a$" + text.substring(4), PASSWORD));
        assertFalse(OpenBSDBCrypt.checkPassword("$2a$" + text.substring(4),
                "wrong".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class,
                () -> ProtonBcrypt.hash(PASSWORD, new byte[8]));
    }

    @Test
    void clientAndServerCompleteTheHandshake() throws Exception {
        int groupBytes = (GROUP_BITS + 7) / 8;
        byte[] modulusRaw = ProtonSrp.toLittleEndian(GROUP, groupBytes);
        byte[] hashed = ProtonSrp.hashPassword(4, PASSWORD, SALT, modulusRaw);
        BigInteger modulus = ProtonSrp.fromLittleEndian(modulusRaw);
        BigInteger x = ProtonSrp.fromLittleEndian(hashed);
        BigInteger verifier = BigInteger.TWO.modPow(x, modulus);

        SecureRandom random = new SecureRandom();
        BigInteger serverSecret = new BigInteger(GROUP_BITS, random).mod(modulus.subtract(BigInteger.ONE));
        BigInteger multiplier = ProtonSrp.fromLittleEndian(ProtonSrp.expandHash(ProtonSrp.concat(
                ProtonSrp.toLittleEndian(BigInteger.TWO, groupBytes), modulusRaw)))
                .mod(modulus);
        BigInteger serverEphemeral = multiplier.multiply(verifier)
                .add(BigInteger.TWO.modPow(serverSecret, modulus)).mod(modulus);

        ProtonSrp client = new ProtonSrp(new SecureRandom(), GROUP_BITS);
        ProtonSrp.Proofs proofs = client.generateProofs(modulusRaw, hashed,
                ProtonSrp.toLittleEndian(serverEphemeral, groupBytes));

        // Server side: recompute and check the client proof.
        BigInteger clientEphemeral = ProtonSrp.fromLittleEndian(proofs.clientEphemeral());
        BigInteger scrambling = ProtonSrp.fromLittleEndian(ProtonSrp.expandHash(ProtonSrp.concat(
                ProtonSrp.toLittleEndian(clientEphemeral, groupBytes),
                ProtonSrp.toLittleEndian(serverEphemeral, groupBytes))));
        BigInteger serverShared = clientEphemeral.multiply(verifier.modPow(scrambling, modulus))
                .modPow(serverSecret, modulus);
        byte[] expectedClientProof = ProtonSrp.expandHash(ProtonSrp.concat(
                ProtonSrp.toLittleEndian(clientEphemeral, groupBytes),
                ProtonSrp.toLittleEndian(serverEphemeral, groupBytes),
                ProtonSrp.toLittleEndian(serverShared, groupBytes)));
        assertArrayEquals(expectedClientProof, proofs.clientProof());

        byte[] serverProof = ProtonSrp.expandHash(ProtonSrp.concat(
                ProtonSrp.toLittleEndian(clientEphemeral, groupBytes),
                expectedClientProof,
                ProtonSrp.toLittleEndian(serverShared, groupBytes)));
        assertArrayEquals(serverProof, proofs.expectedServerProof());
        assertDoesNotThrow(() -> ProtonSrp.verifyServerProof(proofs.expectedServerProof(),
                java.util.Base64.getEncoder().encodeToString(serverProof)));
    }

    @Test
    void tamperedServerProofIsRejected() throws Exception {
        byte[] expected = ProtonSrp.expandHash("session".getBytes(StandardCharsets.UTF_8));
        String tampered = java.util.Base64.getEncoder().encodeToString(
                ProtonSrp.expandHash("other".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalStateException.class,
                () -> ProtonSrp.verifyServerProof(expected, tampered));
        assertThrows(IllegalStateException.class,
                () -> ProtonSrp.verifyServerProof(expected, "!!!not-base64!!!"));
    }

    @Test
    void garbageModulusIsRejected() {
        assertThrows(IllegalStateException.class, () -> ProtonSrp.decodeModulus("not a signed message"));
        assertThrows(IllegalStateException.class, () -> ProtonSrp.decodeModulus(null));
    }

    @Test
    void unsupportedAuthVersionIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ProtonSrp.hashPassword(2, PASSWORD, SALT, new byte[]{1, 2, 3}));
    }

    @Test
    void digestSanity() throws Exception {
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        assertEquals(64, sha512.digest(new byte[0]).length);
    }
}
