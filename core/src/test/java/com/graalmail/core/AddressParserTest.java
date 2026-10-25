/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Address;
import com.graalmail.core.text.AddressParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class AddressParserTest {

    @Test
    void parsesASingleBareAddress() {
        List<Address> addresses = AddressParser.parseList("jane@example.org");
        assertEquals(1, addresses.size());
        assertEquals("jane@example.org", addresses.get(0).email());
        assertEquals("", addresses.get(0).name());
    }

    @Test
    void parsesSeveralSeparatedAddresses() {
        List<Address> addresses = AddressParser.parseList("a@example.org; b@example.org, c@example.org");
        assertEquals(3, addresses.size());
        assertEquals("c@example.org", addresses.get(2).email());
    }

    @Test
    void keepsCommasInsideDisplayNames() {
        List<Address> addresses = AddressParser.parseList("\"Doe, Jane\" <jane@example.org>");
        assertEquals(1, addresses.size());
        assertEquals("Doe, Jane", addresses.get(0).name());
        assertEquals("jane@example.org", addresses.get(0).email());
    }

    @Test
    void ignoresEmptyEntriesAndTrailingSeparators() {
        List<Address> addresses = AddressParser.parseList("a@example.org,, ;");
        assertEquals(1, addresses.size());
    }

    @Test
    void formatsAListForTheField() {
        String formatted = AddressParser.formatList(List.of(
                new Address("Jane Doe", "jane@example.org"), Address.of("bob@example.org")));
        assertEquals("Jane Doe <jane@example.org>, bob@example.org", formatted);
    }

    @Test
    void detectsInvalidAddresses() {
        assertTrue(AddressParser.hasInvalid(List.of(Address.of("not-an-address"))));
        assertFalse(AddressParser.hasInvalid(List.of(Address.of("jane@example.org"))));
    }
}
