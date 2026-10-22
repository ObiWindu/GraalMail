/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.SearchQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SqlMailStoreTest {

    private SqlMailStore store;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void storesAndReadsAccounts() {
        Account account = Account.builder("a1", "gmail")
                .email("user@example.com")
                .displayName("User")
                .setting("imap.host", "imap.gmail.com")
                .build();
        store.saveAccount(account);

        assertEquals(1, store.listAccounts().size());
        Account loaded = store.account("a1").orElseThrow();
        assertEquals("user@example.com", loaded.email());
        assertEquals("imap.gmail.com", loaded.setting("imap.host"));
    }

    @Test
    void storesFoldersAndMessages() {
        store.saveAccount(Account.builder("a1", "gmail").email("u@example.com").build());
        store.saveFolders("a1", List.of(new Folder("inbox", "Inbox", "", FolderType.INBOX, 3, 2,
                true, false, "")));

        Message message = Message.builder("m1")
                .accountId("a1")
                .folderId("inbox")
                .subject("Hello")
                .from(new Address("Jane", "jane@example.com"))
                .to(List.of(new Address("", "me@example.com")))
                .snippet("Body preview")
                .date(Instant.ofEpochMilli(1_700_000_000_000L))
                .unread(true)
                .bodyText("Body text")
                .build();
        store.saveMessage(message);

        assertEquals(1, store.listMessages("a1", "inbox", 20, 0).messages().size());
        Message loaded = store.message("m1").orElseThrow();
        assertEquals("Hello", loaded.subject());
        assertEquals("jane@example.com", loaded.from().email());
        assertTrue(loaded.unread());
        assertEquals("Body text", loaded.bodyText());
    }

    @Test
    void updatesFlagsAndMovesMessages() {
        store.saveAccount(Account.builder("a1", "gmail").email("u@example.com").build());
        store.saveMessage(Message.builder("m1").accountId("a1").folderId("inbox")
                .subject("s").unread(true).flagged(false).build());

        store.updateFlags("m1", false, true);
        Message updated = store.message("m1").orElseThrow();
        assertFalse(updated.unread());
        assertTrue(updated.flagged());

        store.moveMessage("m1", "trash");
        assertEquals("trash", store.message("m1").orElseThrow().folderId());
    }

    @Test
    void searchesCachedMessages() {
        store.saveAccount(Account.builder("a1", "gmail").email("u@example.com").build());
        store.saveMessage(Message.builder("m1").accountId("a1").folderId("inbox")
                .subject("Invoice 2026").bodyText("Please pay").unread(true).build());
        store.saveMessage(Message.builder("m2").accountId("a1").folderId("inbox")
                .subject("Lunch").bodyText("Pizza").unread(false).build());

        List<Message> results = store.search(SearchQuery.builder().text("Invoice").build(), 10);
        assertEquals(1, results.size());
        assertEquals("m1", results.get(0).id());

        List<Message> unread = store.search(SearchQuery.builder().unreadOnly(true).build(), 10);
        assertEquals(1, unread.size());
    }

    @Test
    void managesPendingActionsAndSettings() {
        store.saveAccount(Account.builder("a1", "gmail").email("u@example.com").build());
        PendingAction action = PendingAction.of("p1", "a1", PendingAction.Type.MARK_READ, "m1");
        store.enqueue(action);

        List<PendingAction> pending = store.pendingActions("a1");
        assertEquals(1, pending.size());
        store.updatePendingAction(pending.get(0).withState(PendingAction.State.DONE, 1, ""));
        assertTrue(store.pendingActions("a1").isEmpty());

        store.putSetting("theme", "dark");
        assertEquals("dark", store.setting("theme", "light"));
        assertEquals("light", store.setting("missing", "light"));
    }

    @Test
    void clearsMessageCache() {
        store.saveAccount(Account.builder("a1", "gmail").email("u@example.com").build());
        store.saveMessage(Message.builder("m1").accountId("a1").folderId("inbox").subject("s").build());
        store.clearMessageCache("a1");
        assertTrue(store.listMessages("a1", "inbox", 10, 0).isEmpty());
    }

    @Test
    void deletesAccountAndCascades() {
        store.saveAccount(Account.builder("a1", "gmail").email("u@example.com").build());
        store.saveMessage(Message.builder("m1").accountId("a1").folderId("inbox").subject("s").build());
        store.deleteAccount("a1");
        assertTrue(store.listAccounts().isEmpty());
        assertTrue(store.message("m1").isEmpty());
    }

    @Test
    void persistsContacts() {
        Contact contact = new Contact("c1", "Jane", "jane@example.com", "friend", Instant.now());
        store.saveContact(contact);
        List<Map<String, Object>> rows = store.listContacts().stream()
                .map(c -> Map.<String, Object>of("id", c.id(), "email", c.email()))
                .toList();
        assertEquals(1, rows.size());
        store.deleteContact("c1");
        assertTrue(store.listContacts().isEmpty());
    }
}
