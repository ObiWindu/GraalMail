/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;

/** Deterministic sender avatars in the style of Proton Mail. */
final class ProtonAvatar {

    private static final String[] PALETTE = {
        "#6D4AFF", "#0AA176", "#D18300", "#DB3B6B",
        "#2A8FBD", "#7A5CFF", "#E4562E", "#1D783B",
    };

    private ProtonAvatar() { }

    static StackPane avatar(String name, String email, double size) {
        String initials = initials(name, email);
        String color = colorFor(email.isBlank() ? name : email);
        Label label = new Label(initials);
        label.getStyleClass().add("proton-avatar-label");
        label.setStyle("-fx-text-fill: white; -fx-font-weight: 700;"
                + String.format(java.util.Locale.ROOT, " -fx-font-size: %.1fpx;", size * 0.38));
        StackPane pane = new StackPane(label);
        pane.getStyleClass().add("proton-avatar");
        pane.setAlignment(Pos.CENTER);
        String css = String.format(java.util.Locale.ROOT,
                "-fx-min-width: %.1fpx; -fx-pref-width: %.1fpx; -fx-max-width: %.1fpx;"
                + "-fx-min-height: %.1fpx; -fx-pref-height: %.1fpx; -fx-max-height: %.1fpx;"
                + "-fx-background-color: %s; -fx-background-radius: %.1fpx;",
                size, size, size, size, size, size, color, size / 2);
        pane.setStyle(css);
        pane.setAccessibleText("Avatar for " + (name.isBlank() ? email : name));
        return pane;
    }

    static String initials(String name, String email) {
        String source = name == null ? "" : name.trim();
        if (source.isEmpty()) source = email == null ? "" : email.trim();
        if (source.isEmpty()) return "?";
        if (source.contains("@") && source.indexOf(' ') < 0) {
            String local = source.substring(0, source.indexOf('@'));
            local = local.replaceAll("[._\\-]+", " ").trim();
            if (local.isEmpty()) return "?";
            String[] parts = local.split("\\s+");
            if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(java.util.Locale.ROOT);
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase(java.util.Locale.ROOT);
        }
        String[] parts = source.split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(java.util.Locale.ROOT);
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(java.util.Locale.ROOT);
    }

    private static String colorFor(String key) {
        int hash = 0;
        for (int i = 0; i < key.length(); i++) hash = 31 * hash + Character.toLowerCase(key.charAt(i));
        return PALETTE[Math.floorMod(hash, PALETTE.length)];
    }
}
