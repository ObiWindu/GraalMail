/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import com.graalmail.security.store.AesGcmProtector;
import com.graalmail.security.store.CredentialStore;
import com.graalmail.security.store.FileCredentialStore;
import com.graalmail.security.store.SecretProtector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Builds the desktop credential store.
 *
 * <p><strong>This is not an OS keyring.</strong> GraalMail does not integrate
 * with libsecret, the Secret Service API or any other desktop keychain on
 * Linux, and it does not pretend to. Secrets are encrypted at rest with
 * AES-GCM using a key derived (PBKDF2-HMAC-SHA256, 120000 iterations) from a
 * master passphrase and a randomly generated salt that is stored next to the
 * credential file.</p>
 *
 * <p>The master passphrase is taken from the {@code GRAALMAIL_PASSPHRASE}
 * environment variable when it is set. Otherwise it is kept in a file that only
 * the owner can read ({@code master.passphrase}). That file is a convenience
 * for single user desktops, not a secret: anybody who can read it can read
 * the credentials. Users who need a real protection boundary should start the
 * application with {@code GRAALMAIL_PASSPHRASE} set from their own secret
 * management and protect the data directory accordingly.</p>
 */
public final class DesktopSecretStore {

    public static final String PASSPHRASE_ENVIRONMENT_VARIABLE = "GRAALMAIL_PASSPHRASE";

    private static final Logger LOG = Log.of(DesktopSecretStore.class);

    private static final int SALT_BYTES = 16;
    private static final int PASSPHRASE_BYTES = 32;
    private static final Set<PosixFilePermission> OWNER_ONLY =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private DesktopSecretStore() {
    }

    /** Opens the encrypted credential file, creating the data directory if needed. */
    public static CredentialStore open(AppPaths paths) throws Exception {
        Files.createDirectories(paths.baseDirectory());
        byte[] salt = readOrCreateSalt(paths.saltFile());
        char[] passphrase = resolvePassphrase(paths);
        SecretProtector protector = new AesGcmProtector(passphrase, salt);
        java.util.Arrays.fill(passphrase, '\0');
        return new FileCredentialStore(paths.credentialFile(), protector);
    }

    static byte[] readOrCreateSalt(Path saltFile) throws IOException {
        if (Files.exists(saltFile)) {
            byte[] salt = Files.readAllBytes(saltFile);
            if (salt.length >= SALT_BYTES) {
                return salt;
            }
            LOG.warn("Salt file {} is too short, generating a new one", saltFile.getFileName());
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        Files.createDirectories(saltFile.getParent());
        Files.write(saltFile, salt, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        restrictPermissions(saltFile);
        return salt;
    }

    static char[] resolvePassphrase(AppPaths paths) throws IOException {
        String fromEnvironment = System.getenv(PASSPHRASE_ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.toCharArray();
        }
        Path file = paths.masterPassphraseFile();
        if (Files.exists(file)) {
            String stored = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!stored.isEmpty()) {
                return stored.toCharArray();
            }
        }
        byte[] random = new byte[PASSPHRASE_BYTES];
        new SecureRandom().nextBytes(random);
        String generated = new String(random, StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        Files.write(file, generated.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        restrictPermissions(file);
        LOG.info("Generated a local master passphrase in {} — this is not an OS keyring, protect the "
                + "data directory", file.getFileName());
        return generated.toCharArray();
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException e) {
            // Not a POSIX filesystem. The content is still encrypted at rest.
            LOG.debug("Could not restrict permissions on {}", file.getFileName());
        }
    }
}
