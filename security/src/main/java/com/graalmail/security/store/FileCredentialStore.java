/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * File backed credential store. Secrets are encrypted with a SecretProtector
 * before they touch the disk and the file is only readable by the owner on
 * POSIX systems.
 */
public final class FileCredentialStore implements CredentialStore {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final String SEPARATOR = "::";

    private final Path file;
    private final SecretProtector protector;

    public FileCredentialStore(Path file, SecretProtector protector) {
        this.file = file;
        this.protector = protector;
    }

    @Override
    public String read(String accountId, String key) {
        Properties props = load();
        return props.getProperty(encode(accountId, key));
    }

    @Override
    public void write(String accountId, String key, String value) {
        Properties props = load();
        props.setProperty(encode(accountId, key), value);
        store(props);
    }

    @Override
    public void delete(String accountId) {
        Properties props = load();
        String prefix = accountId + SEPARATOR;
        props.stringPropertyNames().removeIf(name -> name.startsWith(prefix));
        store(props);
    }

    @Override
    public void deleteAll() {
        store(new Properties());
    }

    private static String encode(String accountId, String key) {
        return accountId + SEPARATOR + key;
    }

    private Properties load() {
        Properties props = new Properties();
        if (!Files.exists(file)) {
            return props;
        }
        try {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0) {
                return props;
            }
            byte[] plain = protector.decrypt(raw);
            props.load(new java.io.ByteArrayInputStream(plain));
            return props;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read credential store", e);
        }
    }

    private void store(Properties props) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] plain = toBytes(props);
            byte[] encrypted = protector.encrypt(plain);
            Files.write(file, encrypted, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            restrictPermissions();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write credential store", e);
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: content is still encrypted at rest.
        }
    }

    private byte[] toBytes(Properties props) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            sb.append(escape(String.valueOf(entry.getKey()))).append('=')
                    .append(escape(String.valueOf(entry.getValue()))).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("=", "\\=").replace("\n", "\\n");
    }
}
