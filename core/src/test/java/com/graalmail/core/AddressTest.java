/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Address;
import org.junit.jupiter.api.Test;

class AddressTest {

    @Test
    void parsesDisplayNameAndEmail() {
        Address address = Address.parse("Jane Doe <jane@example.com>");
        assertEquals("Jane Doe", address.name());
        assertEquals("jane@example.com", address.email());
    }

    @Test
    void parsesQuotedDisplayName() {
        Address address = Address.parse("\"Doe, Jane\" <jane@example.com>");
        assertEquals("Doe, Jane", address.name());
        assertEquals("jane@example.com", address.email());
    }

    @Test
    void formatsDisplayForm() {
        assertEquals("Jane Doe <jane@example.com>",
                new Address("Jane Doe", "jane@example.com").format());
        assertEquals("jane@example.com>", "jane@example.com>");
    }

    @Test
    void validatesSyntacticallyValidAddresses() {
        assertTrue(new Address("", "jane@example.com").isSyntacticallyValid());
        assertFalse(new Address("", "jane").isSyntacticallyValid());
        assertFalse(new Address("", "jane@").isSyntacticallyValid());
        assertFalse(new Address("", "@example.com").isSyntacticallyValid());
        assertFalse(new Address("", "a@b@c.com").isSyntacticallyValid());
        assertFalse(new Address("", "jane@example").isSyntacticallyValid());
    }
}
