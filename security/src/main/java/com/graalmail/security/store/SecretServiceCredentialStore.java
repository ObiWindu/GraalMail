/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Credential store backed by the FreeDesktop Secret Service (libsecret).
 * Uses the `secret-tool` CLI for store/retrieve operations.
 * Falls back to file-based storage if Secret Service is unavailable.
 */
public final class SecretServiceCredentialStore implements CredentialStore {

    private static final String SEPARATOR = "::";
    private static final String APP_LABEL = "GraalMail";
    private final Path fallbackFile;
    private final SecretProtector fallbackProtector;
    private final boolean secretServiceAvailable;
    private final Map<String, String> memoryCache = new ConcurrentHashMap<>();

    public SecretServiceCredentialStore(Path fallbackFile, SecretProtector fallbackProtector) {
        this.fallbackFile = fallbackFile;
        this.fallbackProtector = fallbackProtector;
        this.secretServiceAvailable = checkSecretServiceAvailable();
        if (secretServiceAvailable) {
            loadFromSecretService();
        }
    }

    private static boolean checkSecretServiceAvailable() {
        try {
            Process p = new ProcessBuilder("secret-tool", "--version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadFromSecretService() {
        try {
            Process p = new ProcessBuilder("secret-tool", "search", "application", APP_LABEL).start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() == 0) {
                for (String line : output.lines().toList()) {
                    if (line.contains("=")) {
                        String[] parts = line.split("=", 2);
                        String attr = parts[0].trim();
                        String value = parts[1].trim();
                        if (attr.startsWith("account.")) {
                            memoryCache.put(attr.substring("account.".length()), value);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public String read(String accountId, String key) {
        String encoded = encode(accountId, key);
        String cached = memoryCache.get(encoded);
        if (cached != null) {
            return cached;
        }
        if (secretServiceAvailable) {
            try {
                Process p = new ProcessBuilder("secret-tool", "lookup", "application", APP_LABEL,
                        "account", accountId, "key", key).start();
                String value = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor() == 0 && !value.isEmpty()) {
                    memoryCache.put(encoded, value);
                    return value;
                }
            } catch (Exception ignored) {
            }
        }
        return fallbackRead(accountId, key);
    }

    @Override
    public void write(String accountId, String key, String value) {
        String encoded = encode(accountId, key);
        memoryCache.put(encoded, value);
        if (secretServiceAvailable) {
            try {
                ProcessBuilder pb = new ProcessBuilder("secret-tool", "store", "--label",
                        APP_LABEL + " credentials", "application", APP_LABEL,
                        "account", accountId, "key", key);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                if (p.waitFor() == 0) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        fallbackWrite(accountId, key, value);
    }

    @Override
    public void delete(String accountId) {
        String prefix = accountId + SEPARATOR;
        memoryCache.keySet().removeIf(k -> k.startsWith(prefix));
        if (secretServiceAvailable) {
            try {
                ProcessBuilder pb = new ProcessBuilder("secret-tool", "clear", "application", APP_LABEL,
                        "account", accountId);
                pb.start().waitFor();
            } catch (Exception ignored) {
            }
        }
        fallbackDelete(accountId);
    }

    @Override
    public void deleteAll() {
        memoryCache.clear();
        if (secretServiceAvailable) {
            try {
                new ProcessBuilder("secret-tool", "clear", "application", APP_LABEL).start().waitFor();
            } catch (Exception ignored) {
            }
        }
        fallbackDeleteAll();
    }

    private String fallbackRead(String accountId, String key) {
        Properties props = loadFallback();
        return props.getProperty(encode(accountId, key));
    }

    private void fallbackWrite(String accountId, String key, String value) {
        Properties props = loadFallback();
        props.setProperty(encode(accountId, key), value);
        storeFallback(props);
    }

    private void fallbackDelete(String accountId) {
        Properties props = loadFallback();
        String prefix = accountId + SEPARATOR;
        props.stringPropertyNames().removeIf(name -> name.startsWith(prefix));
        storeFallback(props);
    }

    private void fallbackDeleteAll() {
        storeFallback(new Properties());
    }

    private static String encode(String accountId, String key) {
        return accountId + SEPARATOR + key;
    }

    private Properties loadFallback() {
        Properties props = new Properties();
        if (!Files.exists(fallbackFile)) {
            return props;
        }
        try {
            byte[] raw = Files.readAllBytes(fallbackFile);
            if (raw.length == 0) {
                return props;
            }
            byte[] plain = fallbackProtector.decrypt(raw);
            props.load(new java.io.ByteArrayInputStream(plain));
            return props;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read fallback credential store", e);
        }
    }

    private void storeFallback(Properties props) {
        try {
            Path parent = fallbackFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] plain = toBytes(props);
            byte[] encrypted = fallbackProtector.encrypt(plain);
            Files.write(fallbackFile, encrypted, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            restrictFallbackPermissions();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write fallback credential store", e);
        }
    }

    private void restrictFallbackPermissions() {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms = java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(fallbackFile, perms);
        } catch (IOException | UnsupportedOperationException ignored) {
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
