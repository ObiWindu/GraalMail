/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.text;

import java.text.Normalizer;
import java.util.Locale;

/** Small text helpers shared by the UI layers. */
public final class TextTools {

    private TextTools() {
    }

    public static String snippet(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    public static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0)));
        }
        return sb.toString();
    }

    /** Removes control characters and strips path separators from a file name. */
    public static String safeFileName(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String cleaned = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_")
                .replaceAll("^\\.+$", "_")
                .trim();
        if (cleaned.isBlank()) {
            return fallback;
        }
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(cleaned.length() - 180);
        }
        return cleaned;
    }

    public static String lower(Locale locale, String value) {
        return value == null ? "" : value.toLowerCase(locale);
    }
}
