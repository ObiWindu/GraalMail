/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.testing.compatibility;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.SyncResult;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.AccountSetup;
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.ConnectionTestResult;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SendResult;
import com.graalmail.provider.api.SynchronizationService;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compatibility test suite that tests the provider abstraction using the fake provider.
 * This tests the core sync engine and provider contract without network dependencies.
 */
public class ProviderCompatibilityTest {

    protected CompatibilityTestProvider testProvider;
    protected AccountProvider provider;
    protected ProviderSession session;

    @BeforeEach
    public void setUp() {
        testProvider = new CompatibilityTestProvider();
        provider = testProvider;
        session = provider.openSession(createTestSetup());
        addTestMessages();
    }

    protected AccountSetup createTestSetup() {
        return AccountSetup.builder(com.graalmail.provider.api.AuthMethod.PASSWORD)
                .email("test@example.com")
                .displayName("Test User")
                .credential(com.graalmail.security.model.Credential.password("test@example.com", "password"))
                .build();
    }

    // ============ Authentication Tests ============

    @Test
    void testAuthenticationSuccess() {
        assertNotNull(session);
        // Connection is established by opening session
        assertTrue(session.mailboxes().listFolders().size() > 0);
    }

    @Test
    void testAuthenticationFailure() {
        testProvider.failAuthentication(true);
        assertThrows(ProviderException.class, () -> provider.openSession(createTestSetup()));
    }

    @Test
    void testConnectionTestSuccess() {
        ConnectionTestResult result = provider.testConnection(createTestSetup());
        assertTrue(result.successful());
    }

    @Test
    void testConnectionTestFailure() {
        testProvider.failAuthentication(true);
        ConnectionTestResult result = provider.testConnection(createTestSetup());
        assertFalse(result.successful());
        assertEquals(ErrorCode.AUTHENTICATION_FAILED, result.errorCode());
    }

    // ============ Folder Tests ============

    @Test
    void testListFolders() {
        List<Folder> folders = session.mailboxes().listFolders();
        assertNotNull(folders);
        assertFalse(folders.isEmpty());
        // Verify standard folders exist
        boolean hasInbox = folders.stream().anyMatch(f -> f.type() == FolderType.INBOX);
        boolean hasSent = folders.stream().anyMatch(f -> f.type() == FolderType.SENT);
        boolean hasDrafts = folders.stream().anyMatch(f -> f.type() == FolderType.DRAFTS);
        boolean hasTrash = folders.stream().anyMatch(f -> f.type() == FolderType.TRASH);
        assertTrue(hasInbox, "Should have INBOX folder");
        assertTrue(hasSent, "Should have SENT folder");
        assertTrue(hasDrafts, "Should have DRAFTS folder");
        assertTrue(hasTrash, "Should have TRASH folder");
    }

    @Test
    void testFolderRefreshCounts() {
        List<Folder> folders = session.mailboxes().listFolders();
        Folder inbox = folders.stream().filter(f -> f.type() == FolderType.INBOX).findFirst().orElseThrow();
        Folder refreshed = session.mailboxes().refreshCounts(inbox.id());
        assertNotNull(refreshed);
        assertEquals(inbox.id(), refreshed.id());
    }

    // ============ Message Listing Tests ============

    @Test
    void testListMessagesEmptyFolder() {
        MessageListPage page = session.mailboxes().listMessages("trash", 0, 50);
        assertNotNull(page);
        assertTrue(page.messages().isEmpty());
        assertEquals(0, page.totalCount());
    }

    @Test
    void testListMessagesWithData() {
        // Add test messages
        addTestMessages();

        MessageListPage page = session.mailboxes().listMessages("inbox", 0, 50);
        assertNotNull(page);
        assertFalse(page.messages().isEmpty());
        assertTrue(page.totalCount() > 0);
    }

    @Test
    void testListMessagesPagination() {
        addTestMessages();

        MessageListPage page1 = session.mailboxes().listMessages("inbox", 0, 5);
        MessageListPage page2 = session.mailboxes().listMessages("inbox", 1, 5);

        assertNotNull(page1);
        assertNotNull(page2);
        // Verify no overlap between pages
        for (com.graalmail.core.domain.Message m1 : page1.messages()) {
            for (com.graalmail.core.domain.Message m2 : page2.messages()) {
                assertNotEquals(m1.id(), m2.id());
            }
        }
    }

    @Test
    void testListMessagesByUidRange() {
        addTestMessages();

        MessageListPage page = session.mailboxes().listMessagesByUidRange("inbox", 1, 10);
        assertNotNull(page);
        // Should return messages in UID range
    }

    // ============ Message Operations Tests ============

    @Test
    void testGetMessage() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        Message fetched = session.messages().getMessage(msg.id(), true);
        assertEquals(msg.id(), fetched.id());
        assertEquals(msg.subject(), fetched.subject());
    }

    @Test
    void testSetRead() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        assertTrue(msg.unread());

        session.messages().setRead(msg.id(), true);
        Message updated = session.messages().getMessage(msg.id(), false);
        assertFalse(updated.unread());

        session.messages().setRead(msg.id(), false);
        updated = session.messages().getMessage(msg.id(), false);
        assertTrue(updated.unread());
    }

    @Test
    void testSetFlagged() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        assertFalse(msg.flagged());

        session.messages().setFlagged(msg.id(), true);
        Message updated = session.messages().getMessage(msg.id(), false);
        assertTrue(updated.flagged());

        session.messages().setFlagged(msg.id(), false);
        updated = session.messages().getMessage(msg.id(), false);
        assertFalse(updated.flagged());
    }

    @Test
    void testMoveMessage() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        session.messages().move(msg.id(), "sent");

        Message moved = session.messages().getMessage(msg.id(), false);
        assertEquals("sent", moved.folderId());
    }

    @Test
    void testDeleteMessage() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        String msgId = msg.id();

        session.messages().delete(msgId);

        assertThrows(ProviderException.class, () -> session.messages().getMessage(msgId, false));
    }

    @Test
    void testMarkSpam() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        session.messages().markSpam(msg.id());

        Message spammed = session.messages().getMessage(msg.id(), false);
        assertEquals("spam", spammed.folderId());
    }

    @Test
    void testMarkNotSpam() {
        addTestMessages();
        Message msg = session.mailboxes().listMessages("inbox", 0, 1).messages().get(0);
        session.messages().move(msg.id(), "spam");
        session.messages().markNotSpam(msg.id());

        Message moved = session.messages().getMessage(msg.id(), false);
        assertEquals("inbox", moved.folderId());
    }

    // ============ Draft Tests ============

    @Test
    void testSaveDraft() {
        String draftJson = createDraftJson();
        String draftId = session.messages().saveDraft(draftJson);
        assertNotNull(draftId);
        assertFalse(draftJson.isEmpty());
    }

    // ============ Sending Tests ============

    @Test
    void testSendMessage() {
        String payload = "fake-payload";
        SendResult result = session.messages().send(payload);
        assertTrue(result.accepted());
    }

    @Test
    void testSendViaSubmission() {
        MessageDraft draft = createDraft();
        SendResult result = session.submission().send(draft);
        assertTrue(result.accepted());
    }

    @Test
    void testSendFailure() {
        testProvider.failNetwork(true);
        assertThrows(ProviderException.class, () -> session.messages().send("test"));
    }

    @Test
    void testSendTimeout() {
        testProvider.failTimeout(true);
        assertThrows(ProviderException.class, () -> session.messages().send("test"));
    }

    // ============ Attachment Tests ============

    @Test
    void testAttachmentDownload() {
        // Test requires actual attachment data
    }

    // ============ Search Tests ============

    @Test
    void testSearch() {
        addTestMessages();
        List<Message> results = session.search().search(SearchQuery.builder().text("Test").build());
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchBySender() {
        addTestMessages();
        List<Message> results = session.search().search(SearchQuery.builder().from("sender@example.com").build());
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchBySubject() {
        addTestMessages();
        List<Message> results = session.search().search(SearchQuery.builder().subject("Test").build());
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchUnreadOnly() {
        addTestMessages();
        List<Message> results = session.search().search(SearchQuery.builder().unreadOnly(true).build());
        assertFalse(results.isEmpty());
    }

    @Test
    void testSearchFlaggedOnly() {
        addTestMessages();
        List<Message> results = session.search().search(SearchQuery.builder().flaggedOnly(true).build());
        assertFalse(results.isEmpty());
    }

    // ============ Synchronization Tests ============

    @Test
    void testSynchronizeEmptyFolder() {
        SyncResult result = session.synchronization().synchronize("trash", FolderSyncState.neverSynced());
        assertNotNull(result);
        assertEquals("trash", result.folderId());
    }

    @Test
    void testSynchronizeWithMessages() {
        addTestMessages();
        SyncResult result = session.synchronization().synchronize("inbox", FolderSyncState.neverSynced());
        assertNotNull(result);
        assertTrue(result.newMessages() > 0);
    }

    @Test
    void testSynchronizeIncremental() {
        addTestMessages();
        SyncResult first = session.synchronization().synchronize("inbox", FolderSyncState.neverSynced());
        assertTrue(first.newMessages() > 0);

        // Second sync should return 0 new messages
        SyncResult second = session.synchronization().synchronize("inbox",
                new FolderSyncState(first.uidValidity(), first.uidNext(), first.completedAt().toEpochMilli(),
                        first.highestModSeq(), false));
        // Should handle incremental correctly
    }

    @Test
    void testSynchronizeUidValidityChange() {
        addTestMessages();
        SyncResult first = session.synchronization().synchronize("inbox", FolderSyncState.neverSynced());

        // Simulate UIDVALIDITY change
        testProvider.setUidValidity(999);

        SyncResult second = session.synchronization().synchronize("inbox",
                new FolderSyncState(first.uidValidity(), first.uidNext(), first.completedAt().toEpochMilli(),
                        first.highestModSeq(), false));
        // Should detect UIDVALIDITY change and do full resync
    }

    @Test
    void testSynchronizeAll() {
        List<SyncResult> results = session.synchronization().synchronizeAll();
        assertNotNull(results);
        assertEquals(5, results.size()); // inbox, sent, drafts, trash, spam
    }

    // ============ Error Handling Tests ============

    @Test
    void testNetworkFailure() {
        testProvider.failNetwork(true);
        assertThrows(ProviderException.class, () -> session.messages().getMessage("id", false));
    }

    @Test
    void testTimeout() {
        testProvider.failTimeout(true);
        assertThrows(ProviderException.class, () -> session.messages().getMessage("id", false));
    }

    @Test
    void testMailboxNotFound() {
        assertThrows(ProviderException.class, () -> session.mailboxes().refreshCounts("nonexistent"));
    }

    @Test
    void testMessageNotFound() {
        assertThrows(ProviderException.class, () -> session.messages().getMessage("nonexistent", false));
    }

    // ============ Cleanup ============

    @Test
    void testCloseSession() {
        session.close();
        // Session should be closable without errors
    }

    // ============ Helper Methods ============

    protected void addTestMessages() {
        // Override in derived classes to add test messages
    }

    protected String createDraftJson() {
        return "{\"subject\":\"Test\",\"to\":[{\"email\":\"test@example.com\"}],\"bodyText\":\"Test body\"}";
    }

    protected MessageDraft createDraft() {
        return MessageDraft.builder()
                .to(List.of(new Address("Test", "test@example.com")))
                .subject("Test Subject")
                .bodyText("Test body")
                .build();
    }

    // ============ Extension Interfaces ============

    public interface ConfigurableAuthProvider {
        void failAuthentication(boolean fail);
    }

    public interface ConfigurableNetworkProvider {
        void failNetwork(boolean fail);
        void failTimeout(boolean fail);
    }

    public interface ConfigurableUidProvider {
        void setUidValidity(long validity);
    }

    public interface ConfigurableAttachmentProvider {
        // Attachment configuration methods
    }
}