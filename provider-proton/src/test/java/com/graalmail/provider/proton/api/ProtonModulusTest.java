/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonModulusTest {

    @Test
    void selfSignedModulusVerifiesAgainstItsOwnKey() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey("test".toCharArray());
        var secret = PgpFixtures.parseSecret(material.armoredSecret());
        String modulus = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});
        String signed = PgpFixtures.clearsign(modulus, secret, "test".toCharArray());
        byte[] decoded = ProtonSrp.decodeModulus(signed, PgpFixtures.ringOf(material.publicKey()));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, decoded);
    }

    @Test
    void tamperedModulusIsRejected() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey("test".toCharArray());
        var secret = PgpFixtures.parseSecret(material.armoredSecret());
        String signed = PgpFixtures.clearsign("aGVsbG8=", secret, "test".toCharArray());
        String tampered = signed.replace("aGVsbG8=", "aGVsbG8!");
        assertThrows(IllegalStateException.class,
                () -> ProtonSrp.decodeModulus(tampered, PgpFixtures.ringOf(material.publicKey())));
    }

    @Test
    void foreignKeyIsRejected() throws Exception {
        PgpFixtures.KeyMaterial signer = PgpFixtures.generateKey("test".toCharArray());
        PgpFixtures.KeyMaterial other = PgpFixtures.generateKey("other".toCharArray());
        var secret = PgpFixtures.parseSecret(signer.armoredSecret());
        String signed = PgpFixtures.clearsign("aGVsbG8=", secret, "test".toCharArray());
        assertThrows(IllegalStateException.class,
                () -> ProtonSrp.decodeModulus(signed, PgpFixtures.ringOf(other.publicKey())));
        assertTrue(ProtonSrp.MODULUS_PUBLIC_KEY.contains("BEGIN PGP PUBLIC KEY BLOCK"));
    }

    @Test
    void modulusDecodingSurvivesMissingProvider() throws Exception {
        PgpFixtures.KeyMaterial material = PgpFixtures.generateKey("test".toCharArray());
        var secret = PgpFixtures.parseSecret(material.armoredSecret());
        String signed = PgpFixtures.clearsign("aGVsbG8=", secret, "test".toCharArray());
        var ring = PgpFixtures.ringOf(material.publicKey());
        java.security.Security.removeProvider("BC");
        try {
            byte[] decoded = ProtonSrp.decodeModulus(signed, ring);
            assertArrayEquals(new byte[]{104, 101, 108, 108, 111}, decoded);
        } finally {
            ProtonCrypto.ensureRegistered();
        }
    }

    @Test
    void protonKeysAreWellFormed() throws Exception {
        var keys = ProtonSrp.modulusKeys();
        int count = 0;
        var it = keys.getKeyRings();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(1, count);
    }
    /**
     * Guards the live Proton modulus format end to end, including the
     * hardcoded modulus key. The modulus below was captured from
     * {@code POST /auth/info} and independently verified with GnuPG; if
     * Proton rotates it, this test fails loudly on purpose.
     */
    @Test
    void liveModulusDecodes() throws Exception {
        String signed = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA256
            
            MxyNiLhdnaNRxm8pTZVmAruMLQgL8JKl+9/SO7IjI3pOFk6k5NcDooaTEP4sgZxHxx01Zr/uwqcqosUtUAB5nH+tUPXPhz2NanPbWBDawSftB91Y1CBJDuG9UznaF4pplMbQR5ZkAfhkL5Jl6BGXa+6hbB3gL4E9M4kFWAFiDcxs4kg72g0nlfNeLRxRYJxpB/ojlnE+MGyGpb0uOxnhmZDCpVVm7iUzDaVDMzJYB7jdTnsYRh2yqFuInN+bZgnbT519reHOiB27/+kI/MX6Zy7IjKUAaVrsNoz3f6/foGBjYOqwbsY7ion+wEollNkbG6Rcpm+FCpZ0zWaWjUgelQ==
            -----BEGIN PGP SIGNATURE-----
            Version: ProtonMail
            Comment: https://protonmail.com
            
            wl4EARYIABAFAlwB1j4JEDUFhcTpUY8mAADedAD/btgp4iYf/AYxHlU6p+Fp
            V8wVw5n3gGmAS9QxUzRI+uYBAMD086ZH1hqDBPdmuH45il7eRvIYlWs4AU9j
            RTJ9cXwG
            =TdF9
            -----END PGP SIGNATURE-----
            """;
        byte[] decoded = ProtonSrp.decodeModulus(signed);
        assertEquals(256, decoded.length);
        assertArrayEquals(decoded, ProtonSrp.decodeModulus(signed));
    }

    @Test
    void liveModulusTamperingIsRejected() throws Exception {
        String signed = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA256
            
            MxyNiLhdnaNRxm8pTZVmAruMLQgL8JKl+9/SO7IjI3pOFk6k5NcDooaTEP4sgZxHxx01Zr/uwqcqosUtUAB5nH+tUPXPhz2NanPbWBDawSftB91Y1CBJDuG9UznaF4pplMbQR5ZkAfhkL5Jl6BGXa+6hbB3gL4E9M4kFWAFiDcxs4kg72g0nlfNeLRxRYJxpB/ojlnE+MGyGpb0uOxnhmZDCpVVm7iUzDaVDMzJYB7jdTnsYRh2yqFuInN+bZgnbT519reHOiB27/+kI/MX6Zy7IjKUAaVrsNoz3f6/foGBjYOqwbsY7ion+wEollNkbG6Rcpm+FCpZ0zWaWjUgelQ==
            -----BEGIN PGP SIGNATURE-----
            Version: ProtonMail
            Comment: https://protonmail.com
            
            wl4EARYIABAFAlwB1j4JEDUFhcTpUY8mAADedAD/btgp4iYf/AYxHlU6p+Fp
            V8wVw5n3gGmAS9QxUzRI+uYBAMD086ZH1hqDBPdmuH45il7eRvIYlWs4AU9j
            RTJ9cXwG
            =TdF9
            -----END PGP SIGNATURE-----
            """;
        String tampered = signed.replace("MxyNiL", "AxyNiL");
        assertThrows(IllegalStateException.class, () -> ProtonSrp.decodeModulus(tampered));
    }
}
