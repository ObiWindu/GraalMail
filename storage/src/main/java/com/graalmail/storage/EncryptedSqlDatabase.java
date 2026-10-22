/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import com.graalmail.security.store.SecretProtector;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Encrypted SQL database wrapper that encrypts sensitive message columns at rest.
 *
 * <p>This is a phased approach toward full at-rest encryption. Currently it encrypts
 * the following columns in the messages table:
 * - body_text
 * - body_html
 * - headers
 * - attachments
 *
 * <p>The encryption uses a SecretProtector (AES-GCM with PBKDF2 key derivation)
 * whose key is derived from a per-installation master passphrase protected by
 * the platform credential store (Secret Service on Linux, Android Keystore on Android).
 *
 * <p>Future phases:
 * 1. Encrypt folder names and metadata
 * 2. Encrypt contact information
 * 3. Full SQLCipher migration when feasible
 * 4. Encrypted database indexes for searchable encryption
 *
 * <p>Limitations:
 * - Database metadata, indexes, and schema remain unencrypted
 * - Search on encrypted columns requires decryption (not suitable for large-scale search)
 * - Attachment files themselves are not encrypted (only the JSON metadata)
 */
public final class EncryptedSqlDatabase implements SqlDatabase {

    private static final String[] ENCRYPTED_COLUMNS = {
        "body_text", "body_html", "headers", "attachments"
    };

    private final SqlDatabase delegate;
    private final SecretProtector protector;

    public EncryptedSqlDatabase(SqlDatabase delegate, SecretProtector protector) {
        this.delegate = delegate;
        this.protector = protector;
    }

    @Override
    public void execute(String sql, Object... params) {
        delegate.execute(sql, transformParamsForWrite(params));
    }

    @Override
    public int update(String sql, Object... params) {
        return delegate.update(sql, transformParamsForWrite(params));
    }

    @Override
    public List<Map<String, Object>> query(String sql, Object... params) {
        List<Map<String, Object>> rows = delegate.query(sql, params);
        return transformRowsForRead(rows);
    }

    @Override
    public <T> T inTransaction(SqlTransaction<T> transaction) {
        return delegate.inTransaction(transaction);
    }

    @Override
    public void close() {
        delegate.close();
    }

    private Object[] transformParamsForWrite(Object[] params) {
        if (params == null) return params;
        Object[] result = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            result[i] = maybeEncrypt(params[i]);
        }
        return result;
    }

    private List<Map<String, Object>> transformRowsForRead(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            for (String col : ENCRYPTED_COLUMNS) {
                Object value = row.get(col);
                if (value != null && value instanceof String) {
                    row.put(col, decryptIfNeeded((String) value));
                }
            }
        }
        return rows;
    }

    private Object maybeEncrypt(Object value) {
        if (value == null) return null;
        if (value instanceof String str) {
            try {
                byte[] encrypted = protector.encrypt(str.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(encrypted);
            } catch (Exception e) {
                throw new IllegalStateException("Encryption failed", e);
            }
        }
        return value;
    }

    private String decryptIfNeeded(String value) {
        try {
            // Check if value looks like base64 encrypted data (has proper length)
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > 12) { // IV (12) + at least 1 byte ciphertext
                byte[] plain = protector.decrypt(decoded);
                return new String(plain, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Not encrypted or decryption failed, return as-is
        }
        return value;
    }
}
