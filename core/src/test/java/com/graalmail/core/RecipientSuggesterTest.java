/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.recipient.RecipientSuggestion;
import com.graalmail.core.recipient.RecipientSuggester;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecipientSuggesterTest {

    @Test
    void ranksExactEmailMatchesFirst() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("jane@example.com", List.of(
                RecipientSuggestion.contact("John", "john@example.com"),
                RecipientSuggestion.contact("Jane Doe", "jane@example.com")), 10);

        assertEquals(1, result.size());
        assertEquals("jane@example.com", result.get(0).email());
    }

    @Test
    void matchesPrefixesOfTheNameAndTheAddress() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("ja", List.of(
                RecipientSuggestion.contact("John", "john@example.com"),
                RecipientSuggestion.contact("Jane Doe", "jane@example.com")), 10);

        assertEquals(1, result.size());
        assertEquals("Jane Doe", result.get(0).name());
    }

    @Test
    void prefersContactsOverRecentRecipients() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("jane", List.of(
                RecipientSuggestion.recent("Jane Doe", "jane@example.com"),
                RecipientSuggestion.contact("Jane Doe", "jane@example.com")), 10);

        assertEquals(1, result.size());
        assertEquals(RecipientSuggestion.Source.CONTACT, result.get(0).source());
    }

    @Test
    void mergesDuplicatedAddresses() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("", List.of(
                RecipientSuggestion.contact("Jane Doe", "jane@example.com"),
                RecipientSuggestion.recent("Jane", "JANE@example.com")), 10);

        assertEquals(1, result.size());
        assertEquals("Jane Doe", result.get(0).name());
    }

    @Test
    void returnsEveryCandidateForAnEmptyQuery() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("", List.of(
                RecipientSuggestion.contact("A", "a@example.com"),
                RecipientSuggestion.contact("B", "b@example.com")), 10);

        assertEquals(2, result.size());
    }

    @Test
    void respectsTheLimit() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("example", List.of(
                RecipientSuggestion.contact("A", "a@example.com"),
                RecipientSuggestion.contact("B", "b@example.com"),
                RecipientSuggestion.contact("C", "c@example.com")), 2);

        assertEquals(2, result.size());
    }

    @Test
    void ignoresCandidatesWithoutAnAddress() {
        List<RecipientSuggestion> result = RecipientSuggester.suggest("", List.of(
                RecipientSuggestion.contact("No address", "")), 10);

        assertTrue(result.isEmpty());
    }
}
