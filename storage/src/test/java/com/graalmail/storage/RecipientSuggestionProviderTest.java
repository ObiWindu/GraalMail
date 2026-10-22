/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import com.graalmail.core.recipient.RecipientSuggestion;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecipientSuggestionProviderTest {

    private SqlMailStore store;
    private RecipientSuggestionProvider provider;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        provider = new RecipientSuggestionProvider(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void suggestsContactsFromTheAddressBook() {
        store.saveContact(new Contact("c1", "Jane Doe", "jane@example.com", "", Instant.now()));

        List<RecipientSuggestion> result = provider.suggest("jane", 10);

        assertEquals(1, result.size());
        assertEquals("jane@example.com", result.get(0).email());
        assertEquals(RecipientSuggestion.Source.CONTACT, result.get(0).source());
    }

    @Test
    void suggestsTheAddressOfTheOwnAccount() {
        store.saveAccount(Account.builder("a1", "gmail").email("me@example.com")
                .displayName("Me").build());

        List<RecipientSuggestion> result = provider.suggest("me@", 10);

        assertEquals(1, result.size());
        assertEquals(RecipientSuggestion.Source.ACCOUNT, result.get(0).source());
    }

    @Test
    void suggestsRecipientsOfRecentMessages() {
        store.saveAccount(Account.builder("a1", "gmail").email("me@example.com").build());
        store.saveMessage(Message.builder("m1").accountId("a1").folderId("inbox")
                .subject("Hello")
                .to(List.of(new Address("Bob", "bob@example.com")))
                .date(Instant.now())
                .build());

        List<RecipientSuggestion> result = provider.suggest("bob", 10);

        assertEquals(1, result.size());
        assertEquals("bob@example.com", result.get(0).email());
        assertEquals(RecipientSuggestion.Source.RECENT, result.get(0).source());
    }

    @Test
    void returnsNothingForAnUnknownTerm() {
        store.saveContact(new Contact("c1", "Jane Doe", "jane@example.com", "", Instant.now()));

        assertTrue(provider.suggest("zzz", 10).isEmpty());
    }
}
