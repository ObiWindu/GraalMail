/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import java.nio.file.Path;

/**
 * Locations of the files GraalMail keeps on disk. Everything lives under a
 * single per-user data directory so that the user can back it up or delete it
 * in one place.
 */
public final class AppPaths {

    public static final String APP_DIRECTORY_NAME = "GraalMail";

    private static final String DATABASE_FILE = "graalmail.db";
    private static final String CREDENTIAL_FILE = "credentials.bin";
    private static final String SALT_FILE = "credentials.salt";
    private static final String PASSPHRASE_FILE = "master.passphrase";

    private final Path baseDirectory;

    private AppPaths(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    /** The XDG data directory, or {@code ~/.local/share/GraalMail} as a fallback. */
    public static AppPaths userDefault() {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base;
        if (xdg != null && !xdg.isBlank()) {
            base = Path.of(xdg, APP_DIRECTORY_NAME);
        } else {
            base = Path.of(System.getProperty("user.home", "."), ".local", "share", APP_DIRECTORY_NAME);
        }
        return new AppPaths(base);
    }

    public static AppPaths of(Path baseDirectory) {
        return new AppPaths(baseDirectory);
    }

    public Path baseDirectory() {
        return baseDirectory;
    }

    public Path databaseFile() {
        return baseDirectory.resolve(DATABASE_FILE);
    }

    public Path credentialFile() {
        return baseDirectory.resolve(CREDENTIAL_FILE);
    }

    public Path saltFile() {
        return baseDirectory.resolve(SALT_FILE);
    }

    public Path masterPassphraseFile() {
        return baseDirectory.resolve(PASSPHRASE_FILE);
    }

    @Override
    public String toString() {
        return baseDirectory.toString();
    }
}
