package com.graalmail.p2p.identity;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PolyMailException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted local backup and restore of PolyMail key material (spec section 4.4).
 *
 * <p>Losing a private key means losing the messages encrypted to it: encryption does
 * not guarantee recoverability. This kit lets a user export both key pairs sealed
 * with a passphrase (PBKDF2-HMAC-SHA256, AES-256-GCM, versioned format) and restore
 * them on a replacement device. A wrong passphrase or any tampering fails closed.
 */
public class KeyBackupService {
    private static final byte[] MAGIC = "PMBK1".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    public byte[] exportKey(KeyPair signingKeyPair, KeyPair encryptionKeyPair, char[] passphrase) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            SecretKeySpec key = derive(passphrase, salt);
            byte[] payload = encode(signingKeyPair, encryptionKeyPair);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(payload);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(baos)) {
                out.write(MAGIC);
                out.writeInt(salt.length); out.write(salt);
                out.writeInt(iv.length); out.write(iv);
                out.writeInt(ct.length); out.write(ct);
                out.flush();
                return baos.toByteArray();
            }
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "backup export failed", e);
        }
    }

    public RestoredKeys importKey(byte[] backup, char[] passphrase) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(backup))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "not a PolyMail backup");
            }
            byte[] salt = readBytes(in);
            byte[] iv = readBytes(in);
            byte[] ct = readBytes(in);
            SecretKeySpec key = derive(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] payload = cipher.doFinal(ct);
            return decode(payload);
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "backup restore failed: wrong passphrase or tampered backup", e);
        }
    }

    private SecretKeySpec derive(char[] passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] encode(KeyPair signing, KeyPair encryption) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeBytes(out, signing.getPrivate().getEncoded());
            writeBytes(out, signing.getPublic().getEncoded());
            writeBytes(out, encryption.getPrivate().getEncoded());
            writeBytes(out, encryption.getPublic().getEncoded());
            out.flush();
            return baos.toByteArray();
        }
    }

    private static RestoredKeys decode(byte[] payload) throws Exception {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] signPriv = readBytes(in);
            byte[] signPub = readBytes(in);
            byte[] encPriv = readBytes(in);
            byte[] encPub = readBytes(in);
            KeyFactory ed = KeyFactory.getInstance("Ed25519");
            KeyFactory x = KeyFactory.getInstance("X25519");
            return new RestoredKeys(
                    new KeyPair(ed.generatePublic(new X509EncodedKeySpec(signPub)),
                            ed.generatePrivate(new PKCS8EncodedKeySpec(signPriv))),
                    new KeyPair(x.generatePublic(new X509EncodedKeySpec(encPub)),
                            x.generatePrivate(new PKCS8EncodedKeySpec(encPriv))));
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] b) throws Exception {
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBytes(DataInputStream in) throws Exception {
        int len = in.readInt();
        if (len < 0 || len > 10_000_000) throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "corrupt backup");
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }

    public record RestoredKeys(KeyPair signingKeyPair, KeyPair encryptionKeyPair) {}
}
