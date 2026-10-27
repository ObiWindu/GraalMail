package com.graalmail.p2p.crypto;

import com.graalmail.p2p.PolyMailException;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.ProtocolConstants;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

public class DefaultEncryptionService implements EncryptionService {

    private final SecureRandom random = new SecureRandom();

    @Override
    public KeyPair createEncryptionKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("X25519");
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "cannot create X25519 key", e);
        }
    }

    @Override
    public KeyPair createSigningKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "cannot create Ed25519 key", e);
        }
    }

    @Override
    public KeyMaterial createKeyMaterial() {
        return new KeyMaterial(createSigningKeyPair(), createEncryptionKeyPair());
    }

    @Override
    public byte[] sign(KeyPair signingKeyPair, byte[] data) {
        try {
            java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initSign(signingKeyPair.getPrivate());
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "sign failed", e);
        }
    }

    @Override
    public boolean verify(byte[] signingPublicKeyEncoded, byte[] data, byte[] signature) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(signingPublicKeyEncoded));
            java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initVerify(pk);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext, byte[] recipientEncryptionPublicKeyEncoded, byte[] associatedData) {
        try {
            KeyFactory kf = KeyFactory.getInstance("X25519");
            PublicKey recipientPk = kf.generatePublic(new X509EncodedKeySpec(recipientEncryptionPublicKeyEncoded));

            KeyPair ephemeral = createEncryptionKeyPair();
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(ephemeral.getPrivate());
            ka.doPhase(recipientPk, true);
            byte[] shared = ka.generateSecret();

            byte[] salt = new byte[16];
            random.nextBytes(salt);
            byte[] key = hkdf(shared, salt, "PolyMail-X25519-AES".getBytes(java.nio.charset.StandardCharsets.UTF_8), ProtocolConstants.AES_KEY_BYTES);

            byte[] iv = new byte[ProtocolConstants.GCM_IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(ProtocolConstants.GCM_TAG_BITS, iv));
            if (associatedData != null) cipher.updateAAD(associatedData);
            byte[] ct = cipher.doFinal(plaintext);

            byte[] out = new byte[ProtocolConstants.X25519_KEY_BYTES + 16 + iv.length + ct.length];
            System.arraycopy(ephemeral.getPublic().getEncoded(), ephemeral.getPublic().getEncoded().length - ProtocolConstants.X25519_KEY_BYTES, out, 0, ProtocolConstants.X25519_KEY_BYTES);
            // Actually store raw last 32 bytes; X25519 public encoded is 44 bytes with header. Simpler: store full encoded with length prefix.
            // Redo below.
            return buildEnvelope(ephemeral.getPublic().getEncoded(), salt, iv, ct);
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "encryption failed", e);
        }
    }

    private byte[] buildEnvelope(byte[] ephPubEncoded, byte[] salt, byte[] iv, byte[] ct) {
        // length-prefixed: ephPub, salt, iv, ct
        int total = 4 + ephPubEncoded.length + 4 + salt.length + 4 + iv.length + 4 + ct.length;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(total);
        putBytes(buf, ephPubEncoded);
        putBytes(buf, salt);
        putBytes(buf, iv);
        putBytes(buf, ct);
        return buf.array();
    }

    private static void putBytes(java.nio.ByteBuffer buf, byte[] b) {
        buf.putInt(b.length);
        buf.put(b);
    }

    private static byte[] readBytes(java.nio.ByteBuffer buf) {
        if (buf.remaining() < 4) throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "truncated ciphertext envelope");
        int len = buf.getInt();
        if (len < 0 || len > buf.remaining()) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "corrupt ciphertext envelope length");
        }
        byte[] b = new byte[len];
        buf.get(b);
        return b;
    }

    @Override
    public byte[] decrypt(byte[] ciphertextEnvelope, byte[] recipientEncryptionPrivateKeyEncoded, byte[] associatedData) {
        if (ciphertextEnvelope == null || ciphertextEnvelope.length > ProtocolConstants.MAX_MESSAGE_BYTES + 1024) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "ciphertext envelope size invalid");
        }
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(ciphertextEnvelope);
            byte[] ephPubEncoded = readBytes(buf);
            byte[] salt = readBytes(buf);
            byte[] iv = readBytes(buf);
            byte[] ct = readBytes(buf);
            if (buf.hasRemaining()) {
                throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "trailing bytes in ciphertext envelope");
            }

            KeyFactory kf = KeyFactory.getInstance("X25519");
            PrivateKey recipientPriv = kf.generatePrivate(new PKCS8EncodedKeySpec(recipientEncryptionPrivateKeyEncoded));
            PublicKey ephPub = kf.generatePublic(new X509EncodedKeySpec(ephPubEncoded));

            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(recipientPriv);
            ka.doPhase(ephPub, true);
            byte[] shared = ka.generateSecret();

            byte[] key = hkdf(shared, salt, "PolyMail-X25519-AES".getBytes(java.nio.charset.StandardCharsets.UTF_8), ProtocolConstants.AES_KEY_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(ProtocolConstants.GCM_TAG_BITS, iv));
            if (associatedData != null) cipher.updateAAD(associatedData);
            return cipher.doFinal(ct);
        } catch (PolyMailException e) {
            throw e;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "decryption failed", e);
        }
    }

    @Override
    public byte[] seal(byte[] plaintext, byte[] key) {
        if (key == null || key.length != ProtocolConstants.AES_KEY_BYTES) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "content key must be 32 bytes");
        }
        try {
            byte[] iv = new byte[ProtocolConstants.GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(ProtocolConstants.GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "seal failed", e);
        }
    }

    @Override
    public byte[] open(byte[] sealed, byte[] key) {
        if (key == null || key.length != ProtocolConstants.AES_KEY_BYTES) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "content key must be 32 bytes");
        }
        try {
            byte[] iv = java.util.Arrays.copyOf(sealed, ProtocolConstants.GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(ProtocolConstants.GCM_TAG_BITS, iv));
            return cipher.doFinal(sealed, ProtocolConstants.GCM_IV_BYTES, sealed.length - ProtocolConstants.GCM_IV_BYTES);
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "open failed", e);
        }
    }

    @Override
    public byte[] randomContentKey() {
        byte[] key = new byte[ProtocolConstants.AES_KEY_BYTES];
        random.nextBytes(key);
        return key;
    }

    @Override
    public byte[] contentHash(byte[] canonicalBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(canonicalBytes);
        } catch (Exception e) {
            throw new PolyMailException(ErrorCode.ENCRYPTION_FAILED, "hash failed", e);
        }
    }


    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        // RFC 5869
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] t = new byte[0];
        byte[] okm = new byte[length];
        int pos = 0;
        for (int i = 1; pos < length; i++) {
            mac.reset();
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int copy = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, okm, pos, copy);
            pos += copy;
        }
        return okm;
    }
}
