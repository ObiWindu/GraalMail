/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Address;

import java.util.List;

import org.junit.jupiter.api.Test;

class ComposeValidatorTest {

    @Test
    void acceptsAMessageWithOneRecipient() {
        ComposeValidator.Result result = ComposeValidator.validate(
                "jane@example.org", "", "", "Hello");
        assertTrue(result.isValid());
        assertEquals(1, result.to().size());
        assertEquals("jane@example.org", result.to().get(0).email());
    }

    @Test
    void reportsAMissingRecipient() {
        ComposeValidator.Result result = ComposeValidator.validate("", "", "", "Hello");
        assertFalse(result.isValid());
        assertNotNull(result.problemFor("to"));
    }

    @Test
    void reportsAMissingBody() {
        ComposeValidator.Result result = ComposeValidator.validate("jane@example.org", "", "", "");
        assertFalse(result.isValid());
        assertNotNull(result.problemFor("body"));
    }

    @Test
    void reportsAnInvalidAddress() {
        ComposeValidator.Result result = ComposeValidator.validate(
                "not-an-address", "", "", "Hello");
        assertFalse(result.isValid());
        assertNotNull(result.problemFor("to"));
    }

    @Test
    void parsesSeveralRecipients() {
        List<Address> addresses = ComposeValidator.parse("a@example.org; b@example.org");
        assertEquals(2, addresses.size());
    }
}
