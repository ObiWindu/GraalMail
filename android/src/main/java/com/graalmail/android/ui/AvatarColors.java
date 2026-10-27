/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.ui;

import java.util.Locale;

/**
 * Deterministic sender avatars in the style of Thunderbird for Android:
 * a colored circle with the initials of the sender.
 */
final class AvatarColors {

    /** Material-ish palette that stays legible in light and dark themes. */
    static final int[] PALETTE = {
            0xFF6D4AFF, 0xFF0AA176, 0xFFB26A00, 0xFFDB3B6B,
            0xFF2A8FBD, 0xFF7A5CFF, 0xFFD34A2E, 0xFF1D783B,
    };

    private AvatarColors() { }

    /** Background color for the sender, stable across sessions. */
    static int colorFor(String name, String email) {
        String key = email == null || email.isBlank() ? (name == null ? "" : name) : email;
        int hash = 0;
        for (int i = 0; i < key.length(); i++) {
            hash = 31 * hash + Character.toLowerCase(key.charAt(i));
        }
        return PALETTE[Math.floorMod(hash, PALETTE.length)];
    }

    /** One or two initials for the sender. */
    static String initials(String name, String email) {
        String source = name == null ? "" : name.trim();
        if (source.isEmpty()) source = email == null ? "" : email.trim();
        if (source.isEmpty()) return "?";
        if (source.contains("@") && source.indexOf(' ') < 0) {
            String local = source.substring(0, source.indexOf('@')).replaceAll("[._\\-]+", " ").trim();
            if (local.isEmpty()) return "?";
            String[] parts = local.split("\\s+");
            if (parts.length == 1) {
                return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
            }
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1))
                    .toUpperCase(Locale.ROOT);
        }
        String[] parts = source.split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase(Locale.ROOT);
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.ROOT);
    }
}
