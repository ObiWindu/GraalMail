/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.text;

import com.graalmail.core.domain.Address;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the recipient fields of the compose window.
 *
 * <p>The parser understands the forms a user types by hand: a bare address, a
 * display name with an address in angle brackets and a quoted display name.
 * Several addresses can be separated by commas or semicolons.</p>
 */
public final class AddressParser {

    private AddressParser() {
    }

    /**
     * Parses a recipient list.
     *
     * <p>Empty entries are skipped, so a trailing comma is harmless. An entry
     * that has no address at all is dropped as well.</p>
     *
     * @param input the raw text of the field, may be {@code null}
     * @return the parsed addresses in the order of the input
     */
    public static List<Address> parseList(String input) {
        List<Address> addresses = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return addresses;
        }
        for (String part : split(input)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Address address = Address.parse(trimmed);
            if (address.hasEmail()) {
                addresses.add(address);
            }
        }
        return addresses;
    }

    /**
     * Formats addresses for a recipient field.
     *
     * @param addresses the addresses, may be {@code null}
     * @return the formatted list, separated by commas
     */
    public static String formatList(List<Address> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Address address : addresses) {
            if (address == null || !address.hasEmail()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(address.format());
        }
        return sb.toString();
    }

    /**
     * Reports whether an address list holds an entry that cannot be delivered.
     *
     * @param addresses the addresses, may be {@code null}
     * @return {@code true} when at least one entry is syntactically invalid
     */
    public static boolean hasInvalid(List<Address> addresses) {
        if (addresses == null) {
            return false;
        }
        for (Address address : addresses) {
            if (address != null && address.hasEmail() && !address.isSyntacticallyValid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Splits a recipient list at the separators that are not inside an angle
     * bracket pair or a quoted display name.
     *
     * @param input the raw text
     * @return the parts of the list
     */
    static List<String> split(String input) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inAngle = false;
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"' && !inAngle) {
                inQuotes = !inQuotes;
            } else if (c == '<' && !inQuotes) {
                inAngle = true;
            } else if (c == '>' && !inQuotes) {
                inAngle = false;
            }
            if (!inAngle && !inQuotes && (c == ',' || c == ';')) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }
}
