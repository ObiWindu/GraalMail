/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.util.Locale;
import java.util.Objects;

/** An immutable mail address with an optional display name. */
public final class Address {
    private final String name;
    private final String email;

    public Address(String name, String email) {
        this.name = name == null ? "" : name.trim();
        this.email = email == null ? "" : email.trim();
    }

    public static Address of(String email) {
        return new Address("", email);
    }

    public static Address parse(String raw) {
        if (raw == null) {
            return new Address("", "");
        }
        String value = raw.trim();
        int open = value.lastIndexOf('<');
        int close = value.lastIndexOf('>');
        if (open >= 0 && close > open) {
            String display = value.substring(0, open).trim();
            if (display.startsWith("\"") && display.endsWith("\"") && display.length() >= 2) {
                display = display.substring(1, display.length() - 1);
            }
            return new Address(display, value.substring(open + 1, close).trim());
        }
        return new Address("", value);
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public boolean hasEmail() {
        return !email.isEmpty();
    }

    /** RFC 5322 display form, e.g. {@code "Jane Doe" <jane@example.com>}. */
    public String format() {
        if (name.isEmpty()) {
            return email;
        }
        if (name.matches(".*[,<>@:;\"\\[\\]].*")) {
            return "\"" + name.replace("\"", "'") + "\" <" + email + ">";
        }
        return name + " <" + email + ">";
    }

    public String displayLabel() {
        return name.isEmpty() ? email : name;
    }

    /** Local-part check only; domain validity is provider dependent. */
    public boolean isSyntacticallyValid() {
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false;
        }
        String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
        return domain.contains(".") && !domain.startsWith(".") && !domain.endsWith(".")
                && !domain.contains("..");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Address other)) {
            return false;
        }
        return email.equalsIgnoreCase(other.email) && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name.toLowerCase(Locale.ROOT), email.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return format();
    }
}
