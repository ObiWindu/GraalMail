/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.provider.proton.ProtonApiSession;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end session test against a stub Proton API with real PGP fixtures:
 * login is stubbed, but subjects and bodies are genuinely encrypted and
 * decrypted through the session.
 */
class ProtonApiSessionTest {

    private static final byte[] MAILBOX_PASSWORD = "mailbox-secret".getBytes(StandardCharsets.UTF_8);

    private static final class Fixture implements AutoCloseable {
        final StubProtonHttp stub = new StubProtonHttp();
        final PgpFixtures.KeyMaterial material;
        final ProtonApiSession session;

        Fixture() throws Exception {
            material = PgpFixtures.generateKey("mailbox-secret".toCharArray());
            String keyJson = "{\"ID\":\"k1\",\"Fingerprint\":\"fp\",\"Primary\":1,\"Active\":1,"
                    + "\"PrivateKey\":\"" + jsonEscape(material.armoredSecret())
                    + "\",\"Token\":\"\",\"Signature\":\"\"}";
            stub.respond(200, "{\"Code\":1000,\"User\":{\"ID\":\"u1\",\"Name\":\"Test\","
                    + "\"Keys\":[" + keyJson + "]}}");
            stub.respond(200, "{\"Code\":1000,\"KeySalts\":[]}");
            stub.respond(200, "{\"Code\":1000,\"Addresses\":[{\"ID\":\"a1\","
                    + "\"Email\":\"test@proton.me\",\"DisplayName\":\"Test\",\"Status\":1,"
                    + "\"Send\":1,\"Keys\":[" + keyJson + "]}]}");
            stub.respond(200, labelsJson());
            var transport = new DefaultProtonHttpTransport(
                    "http://127.0.0.1:" + stub.port(), "test@1", 2_000, 2_000);
            var tokens = new ProtonAuth.Tokens("uid-1", "access-1", "refresh-1", null, "full", 1);
            var client = new ProtonApiClient(transport, new ProtonAuth(transport), tokens);
            session = new ProtonApiSession("test@proton.me", client, MAILBOX_PASSWORD, "test@proton.me");
            session.unlock();
            stub.reset();
        }

        String encrypted(String text) throws Exception {
            return PgpFixtures.encrypt(material.publicKey(),
                    text.getBytes(StandardCharsets.UTF_8), true);
        }

        @Override
        public void close() throws Exception {
            stub.close();
        }
    }

    private static String labelsJson() {
        return "{\"Code\":1000,\"Labels\":["
                + "{\"ID\":\"0\",\"Name\":\"Inbox\",\"Color\":\"\",\"Type\":1,\"Display\":1,\"Notify\":1,\"Order\":1},"
                + "{\"ID\":\"3\",\"Name\":\"Trash\",\"Color\":\"\",\"Type\":1,\"Display\":1,\"Notify\":1,\"Order\":3},"
                + "{\"ID\":\"10\",\"Name\":\"Starred\",\"Color\":\"\",\"Type\":1,\"Display\":1,\"Notify\":1,\"Order\":10}]}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String messageJson(String id, String subject, String sender, long time,
                                      int unread, String labelIds) {
        return "{\"ID\":\"" + id + "\",\"ConversationID\":\"c-" + id + "\",\"Subject\":\""
                + jsonEscape(subject) + "\",\"Unread\":" + unread + ",\"Type\":1,"
                + "\"Sender\":{\"Address\":\"" + sender + "\",\"Name\":\"Sender " + sender + "\"},"
                + "\"ToList\":[],\"CCList\":[],\"BCCList\":[],\"Time\":" + time + ",\"Size\":100,"
                + "\"NumAttachments\":0,\"IsEncrypted\":1,\"Body\":\"\","
                + "\"MIMEType\":\"\",\"ExternalID\":\"ext-" + id + "\",\"LabelIDs\":[" + labelIds + "],"
                + "\"AddressID\":\"a1\"}";
    }

    @Test
    void listsFoldersWithCounts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.stub.respond(200, "{\"Code\":1000,\"Counts\":["
                    + "{\"LabelID\":\"0\",\"Total\":2,\"Unread\":1},"
                    + "{\"LabelID\":\"3\",\"Total\":0,\"Unread\":0}]}");
            fixture.stub.respond(200, labelsJson());
            var folders = fixture.session.mailboxes().listFolders();
            assertEquals(3, folders.size());
            var inbox = folders.stream().filter(f -> f.id().equals("0")).findFirst().orElseThrow();
            assertEquals(FolderType.INBOX, inbox.type());
            assertEquals("Inbox", inbox.name());
            assertEquals(2, inbox.totalCount());
            assertEquals(1, inbox.unreadCount());
            var starred = folders.stream().filter(f -> f.id().equals("10")).findFirst().orElseThrow();
            assertEquals(FolderType.CUSTOM, starred.type());
        }
    }

    @Test
    void listsMessagesWithDecryptedSubjects() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String first = fixture.encrypted("Subject: Hello one");
            String second = fixture.encrypted("Subject: Hello two");
            fixture.stub.respond(200, "{\"Code\":1000,\"Total\":2,\"Messages\":["
                    + messageJson("m1", first, "alice@example.com", 1_700_000_000L, 1, "\"0\"") + ","
                    + messageJson("m2", second, "bob@example.com", 1_699_000_000L, 0, "\"0\",\"10\"")
                    + "]}");
            var page = fixture.session.mailboxes().listMessages("0", 1, 50);
            assertEquals(2, page.messages().size());
            Message firstMessage = page.messages().get(0);
            assertEquals("Hello one", firstMessage.subject());
            assertEquals("alice@example.com", firstMessage.from().email());
            assertTrue(firstMessage.unread());
            assertFalse(firstMessage.flagged());
            Message secondMessage = page.messages().get(1);
            assertEquals("Hello two", secondMessage.subject());
            assertTrue(secondMessage.flagged());
            assertEquals("ext-m1", firstMessage.internetMessageId());
        }
    }

    @Test
    void readsFullMessageWithBody() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String mime = "Subject: Full hello\r\nContent-Type: text/plain\r\n\r\nBody one here.";
            String body = fixture.encrypted(mime);
            String subject = fixture.encrypted("Subject: Full hello");
            String full = messageJson("m9", subject, "alice@example.com", 1_700_000_000L, 1, "\"0\"");
            full = full.replace("\"Body\":\"\"", "\"Body\":\"" + jsonEscape(body) + "\"");
            fixture.stub.respond(200, "{\"Code\":1000,\"Message\":" + full + "}");
            Message message = fixture.session.messages().getMessage("m9", true);
            assertEquals("Full hello", message.subject());
            assertTrue(message.bodyText().contains("Body one here."));
            assertTrue(message.snippet().contains("Body one here."));
        }
    }

    @Test
    void flagAndReadAndMoveHitTheRightEndpoints() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.session.messages().setRead("m1", true);
            fixture.session.messages().setFlagged("m1", true);
            fixture.session.messages().setFlagged("m1", false);
            var received = fixture.stub.received();
            assertEquals("/messages/read", received.get(0).path());
            assertTrue(received.get(0).body().contains("m1"));
            assertEquals("/messages/label", received.get(1).path());
            assertTrue(received.get(1).body().contains("\"10\""));
            assertEquals("/messages/unlabel", received.get(2).path());

            String full = messageJson("m1",
                    fixture.encrypted("Subject: x"), "a@b.c", 1_700_000_000L, 1, "\"0\"");
            fixture.stub.respond(200, "{\"Code\":1000,\"Message\":" + full + "}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.session.messages().move("m1", "6");
            var moved = fixture.stub.received();
            assertEquals("/messages/m1", moved.get(3).path());
            assertEquals("/messages/unlabel", moved.get(4).path());
            assertEquals("/messages/label", moved.get(5).path());
            assertTrue(moved.get(5).body().contains("\"6\""));
        }
    }

    @Test
    void deleteMovesToTrashFirst() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String full = messageJson("m1",
                    fixture.encrypted("Subject: x"), "a@b.c", 1_700_000_000L, 1, "\"0\"");
            fixture.stub.respond(200, "{\"Code\":1000,\"Message\":" + full + "}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.session.messages().delete("m1");
            var received = fixture.stub.received();
            assertEquals("/messages/m1", received.get(0).path());
            assertEquals("/messages/unlabel", received.get(1).path());
            assertEquals("/messages/label", received.get(2).path());
            assertTrue(received.get(2).body().contains("\"3\""));

            String trashed = messageJson("m2",
                    fixture.encrypted("Subject: y"), "a@b.c", 1_700_000_000L, 0, "\"3\"");
            fixture.stub.respond(200, "{\"Code\":1000,\"Message\":" + trashed + "}");
            fixture.stub.respond(200, "{\"Code\":1000}");
            fixture.session.messages().delete("m2");
            assertEquals("/messages/m2", fixture.stub.received().get(3).path());
            assertEquals("/messages/delete", fixture.stub.received().get(4).path());
        }
    }

    @Test
    void searchFiltersDecryptedSubjects() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String first = fixture.encrypted("Subject: Invoice March");
            String second = fixture.encrypted("Subject: Holiday photos");
            fixture.stub.respond(200, "{\"Code\":1000,\"Total\":2,\"Messages\":["
                    + messageJson("m1", first, "shop@example.com", 1_700_000_000L, 1, "\"5\"") + ","
                    + messageJson("m2", second, "friend@example.com", 1_699_000_000L, 0, "\"5\"")
                    + "]}");
            var query = com.graalmail.core.domain.SearchQuery.builder()
                    .text("invoice").limit(50).build();
            var results = fixture.session.search().search(query);
            assertEquals(1, results.size());
            assertEquals("Invoice March", results.get(0).subject());
            assertTrue(fixture.session.search().isServerSide());
        }
    }

    @Test
    void synchronizeMapsUidsForCacheFill() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String first = fixture.encrypted("Subject: One");
            fixture.stub.respond(200, "{\"Code\":1000,\"Total\":1,\"Messages\":["
                    + messageJson("m1", first, "a@b.c", 1_700_000_000L, 1, "\"0\"") + "]}");
            var never = new com.graalmail.provider.api.FolderSyncState(0, 0, 0, "", true);
            var firstSync = fixture.session.synchronization().synchronize("0", never);
            assertTrue(firstSync.isSuccessful());
            assertEquals(0, firstSync.newMessages());
            assertEquals(1, firstSync.newUids().size());
            assertEquals(1, firstSync.uidValidity());
            assertEquals(1_700_000_000L, firstSync.uidNext());

            var ranged = fixture.session.mailboxes().listMessagesByUidRange(
                    "0", firstSync.newUids().get(0), firstSync.newUids().get(0));
            assertEquals(1, ranged.messages().size());
            assertEquals("m1", ranged.messages().get(0).id());

            fixture.stub.respond(200, "{\"Code\":1000,\"Total\":1,\"Messages\":["
                    + messageJson("m1", first, "a@b.c", 1_700_000_000L, 1, "\"0\"") + "]}");
            var steady = new com.graalmail.provider.api.FolderSyncState(
                    1, 1_700_000_000L, 0, "", false);
            var secondSync = fixture.session.synchronization().synchronize("0", steady);
            assertEquals(0, secondSync.newMessages());
            assertTrue(secondSync.newUids().isEmpty());

            String second = fixture.encrypted("Subject: Two");
            fixture.stub.respond(200, "{\"Code\":1000,\"Total\":2,\"Messages\":["
                    + messageJson("m2", second, "a@b.c", 1_700_000_100L, 1, "\"0\"") + ","
                    + messageJson("m1", first, "a@b.c", 1_700_000_000L, 1, "\"0\"") + "]}");
            var thirdSync = fixture.session.synchronization().synchronize("0", steady);
            assertEquals(1, thirdSync.newMessages());
            assertEquals(1, thirdSync.newUids().size());
        }
    }

    @Test
    void sendingIsClearlyUnsupported() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var draft = com.graalmail.core.domain.MessageDraft.builder()
                    .accountId("test@proton.me").subject("s").bodyText("b").build();
            assertThrows(com.graalmail.core.error.ProviderException.class,
                    () -> fixture.session.submission().send(draft));
            assertThrows(com.graalmail.core.error.ProviderException.class,
                    () -> fixture.session.messages().saveDraft("{}"));
        }
    }
}
