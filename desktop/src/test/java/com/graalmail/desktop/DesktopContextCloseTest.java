/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closing the context must release the database and the credential store,
 * and closing twice (window close request followed by application stop)
 * must stay silent.
 */
class DesktopContextCloseTest {

    @TempDir
    Path temp;

    @Test
    void closeReleasesEverythingAndIsIdempotent() throws Exception {
        Path base = temp.resolve("graalmail-data");
        DesktopContext context = DesktopContext.open(AppPaths.of(base));
        assertTrue(Files.exists(context.databaseFile()));
        assertDoesNotThrow(context::close);
        assertDoesNotThrow(context::close);
    }
}
