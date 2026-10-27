/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every icon referenced in the desktop sources must resolve to a shipped SVG.
 * A missing icon crashes application startup (JavaFX reads them eagerly while
 * building the main window), so this test fails the build instead of the user.
 */
class ThunderbirdIconsTest {

    private static final List<String> REFERENCED = List.of(
            "account-settings", "archive", "attachment", "collapse",
            "display-options", "draft", "filter", "folder", "forward",
            "get-mail", "more", "new-mail", "reply", "search", "sent",
            "settings", "spaces-menu", "spam", "star", "trash", "unread",
            "inbox", "mail", "address-book", "calendar", "chat", "tasks",
            "sync", "outbox");

    @Test
    void allReferencedIconsLoad() {
        for (String name : REFERENCED) {
            var icon = assertDoesNotThrow(() -> ThunderbirdIcons.icon(name),
                    "Missing Thunderbird icon: " + name);
            assertFalse(icon.getChildren().isEmpty(), "Empty icon: " + name);
        }
    }

    @Test
    void rotatedVariantLoads() {
        var icon = assertDoesNotThrow(
                () -> ThunderbirdIcons.icon("collapse", "toolbar-icon"));
        assertDoesNotThrow(() -> icon.setRotate(180));
    }
}
