/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.MessageDraft;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderCapabilities;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.ProviderType;
import com.graalmail.provider.api.SendResult;
import com.graalmail.security.store.CredentialStore;
import com.graalmail.security.store.InMemoryCredentialStore;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.PendingAction;
import com.graalmail.storage.SqlMailStore;
import com.graalmail.sync.AccountSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SyncEngineSendTest {

    private MailStore store;
    private SyncEngine syncEngine;
    private TestProviderSession testSession;
    private AccountSessionFactory sessionFactory;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        
        // Create test account
        Account account = Account.builder("a1", "test")
                .email("test@example.com")
                .build();
        store.saveAccount(account);
        store.saveFolders("a1", List.of(new Folder("inbox", "Inbox", "", FolderType.INBOX, 0, 0, true, false, "")));
        
        testSession = new TestProviderSession();
        
        // Create a session factory that returns our test session
        CredentialStore credentialStore = new InMemoryCredentialStore();
        sessionFactory = new AccountSessionFactory(credentialStore) {
            @Override
            public ProviderSession open(Account account) {
                return testSession;
            }
        };
        
        syncEngine = new SyncEngine(store, sessionFactory);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void testSuccessfulSendMarksActionDone() {
        // Queue a send action with draft payload
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        store.enqueue(action);

        // Configure test session to return accepted
        testSession.setSendResult(SendResult.accepted("provider-msg-1"));

        // Apply pending actions
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Verify action is marked DONE
        List<PendingAction> pending = store.pendingActions("a1");
        assertTrue(pending.isEmpty(), "Action should be marked DONE and removed from pending");
    }

    @Test
    void testAmbiguousSendKeepsActionPending() {
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        store.enqueue(action);

        // Configure test session to return ambiguous result
        testSession.setSendResult(SendResult.ambiguous("Network timeout"));

        // Apply pending actions
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Verify action is NOT marked DONE, but has SUBMISSION_OUTCOME_UNKNOWN state
        List<PendingAction> pending = store.pendingActions("a1");
        assertEquals(1, pending.size());
        PendingAction updated = pending.get(0);
        assertEquals(PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN, updated.sendState());
        assertEquals(PendingAction.State.PENDING, updated.state());
    }

    @Test
    void testFailedBeforeSubmissionMarksActionFailed() {
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        store.enqueue(action);

        // Configure test session to throw authentication error
        testSession.setSendException(new ProviderException(ErrorCode.AUTHENTICATION_FAILED, "Auth failed", false));

        // Apply pending actions
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Verify action is marked FAILED (not retryable)
        List<PendingAction> pending = store.pendingActions("a1");
        assertEquals(1, pending.size());
        PendingAction updated = pending.get(0);
        assertEquals(PendingAction.SendState.FAILED_BEFORE_SUBMISSION, updated.sendState());
        assertEquals(PendingAction.State.FAILED, updated.state());
    }

    @Test
    void testNetworkErrorBeforeSubmissionRetries() {
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        store.enqueue(action);

        // Configure test session to throw network error
        testSession.setSendException(new ProviderException(ErrorCode.NETWORK_UNAVAILABLE, "Network down", true));

        // Apply pending actions
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Verify action is kept PENDING with SUBMISSION_OUTCOME_UNKNOWN
        List<PendingAction> pending = store.pendingActions("a1");
        assertEquals(1, pending.size());
        PendingAction updated = pending.get(0);
        assertEquals(PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN, updated.sendState());
        assertEquals(PendingAction.State.PENDING, updated.state());
    }

    @Test
    void testTimeoutAfterSubmissionKeepsActionPending() {
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        action = action.withSendState(PendingAction.SendState.SUBMITTING); // Already in SUBMITTING state
        store.enqueue(action);

        // Configure test session to throw timeout
        testSession.setSendException(new ProviderException(ErrorCode.TIMEOUT, "Timeout", true));

        // Apply pending actions
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Verify action stays with SUBMISSION_OUTCOME_UNKNOWN
        List<PendingAction> pending = store.pendingActions("a1");
        assertEquals(1, pending.size());
        PendingAction updated = pending.get(0);
        assertEquals(PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN, updated.sendState());
        assertEquals(PendingAction.State.PENDING, updated.state());
    }

    @Test
    void testExplicitUserRecoveryFromUnknownOutcome() {
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        action = action.withSendState(PendingAction.SendState.SUBMISSION_OUTCOME_UNKNOWN);
        store.enqueue(action);

        // User explicitly retries - UI should reset state to QUEUED
        action = store.pendingActions("a1").get(0).withSendState(PendingAction.SendState.QUEUED);
        store.updatePendingAction(action);

        // User explicitly retries - action should be resubmitted
        testSession.setSendResult(SendResult.accepted("provider-msg-1"));

        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Verify action is marked DONE
        List<PendingAction> pending = store.pendingActions("a1");
        assertTrue(pending.isEmpty(), "Action should be marked DONE after user retry");
    }

    @Test
    void testRetryLimitAndCancellation() {
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        PendingAction action = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        // Set attempts to max - 1 (retryPolicy.maxAttempts() is 5 by default)
        action = new PendingAction(action.id(), action.accountId(), action.type(), action.targetId(),
                action.targetFolderId(), action.payload(), action.state(), 4, action.lastError(),
                action.createdAt(), action.sendState());
        store.enqueue(action);

        // Throw network error
        testSession.setSendException(new ProviderException(ErrorCode.NETWORK_UNAVAILABLE, "Network down", true));

        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);

        // Should be marked FAILED after max attempts (5)
        List<PendingAction> pending = store.pendingActions("a1");
        assertEquals(1, pending.size());
        PendingAction updated = pending.get(0);
        assertEquals(PendingAction.State.FAILED, updated.state());
    }

    @Test
    void testDoneNeverRecordedIncorrectly() {
        // This test verifies that DONE is never recorded for ambiguous/failed sends
        String draftJson = createDraftJson("Test Subject", "test@example.com", "Body text");
        
        // Test 1: Ambiguous result
        PendingAction action1 = PendingAction.of("action1", "a1", PendingAction.Type.SEND, "msg1", draftJson);
        store.enqueue(action1);
        testSession.setSendResult(SendResult.ambiguous("Timeout"));
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);
        List<PendingAction> pending = store.pendingActions("a1");
        assertFalse(pending.stream().anyMatch(a -> a.state() == PendingAction.State.DONE),
                "DONE should not be recorded for ambiguous send");

        // Clean up
        store.updatePendingAction(pending.get(0).withState(PendingAction.State.FAILED, 1, "test"));

        // Test 2: Failed before submission
        PendingAction action2 = PendingAction.of("action2", "a1", PendingAction.Type.SEND, "msg2", draftJson);
        store.enqueue(action2);
        testSession.setSendException(new ProviderException(ErrorCode.AUTHENTICATION_FAILED, "Auth failed", false));
        syncEngine.applyPendingActions(store.account("a1").orElseThrow(), testSession);
        pending = store.pendingActions("a1");
        assertFalse(pending.stream().anyMatch(a -> a.state() == PendingAction.State.DONE),
                "DONE should not be recorded for failed send");
    }

    private String createDraftJson(String subject, String to, String bodyText) {
        // Create JSON matching MessageDraft structure for Jackson deserialization
        return String.format("{\"id\":\"\",\"accountId\":\"\",\"to\":[{\"name\":\"\",\"email\":\"%s\"}],\"cc\":[],\"bcc\":[],\"subject\":\"%s\",\"bodyText\":\"%s\",\"bodyHtml\":\"\",\"attachments\":[],\"inReplyTo\":\"\",\"references\":\"\",\"replyToFolderId\":\"\"}", 
                to, subject.replace("\"", "\\\""), bodyText.replace("\"", "\\\""));
    }

    // Test provider session that allows configuring send behavior
    private static class TestProviderSession implements ProviderSession {
        private SendResult sendResult = SendResult.accepted("test");
        private ProviderException sendException = null;

        public void setSendResult(SendResult result) {
            this.sendResult = result;
            this.sendException = null;
        }

        public void setSendException(ProviderException ex) {
            this.sendException = ex;
            this.sendResult = null;
        }

        @Override
        public MailboxService mailboxes() {
            return new MailboxService() {
                @Override
                public List<Folder> listFolders() {
                    return List.of(new Folder("inbox", "Inbox", "", FolderType.INBOX, 0, 0, true, false, ""));
                }

                @Override
                public com.graalmail.core.domain.MessageListPage listMessages(String folderId, int page, int pageSize) {
                    return new com.graalmail.core.domain.MessageListPage(List.of(), false, 0);
                }

                @Override
                public com.graalmail.core.domain.MessageListPage listMessagesByUidRange(String folderId, long startUid, long endUid) {
                    return new com.graalmail.core.domain.MessageListPage(List.of(), false, 0);
                }

                @Override
                public Folder refreshCounts(String folderId) {
                    return new Folder(folderId, folderId, "", FolderType.INBOX, 0, 0, true, false, "");
                }
            };
        }

        @Override
        public MessageService messages() {
            return new MessageService() {
                @Override
                public com.graalmail.core.domain.Message getMessage(String messageId, boolean includeBody) {
                    return null;
                }

                @Override
                public void setRead(String messageId, boolean read) {}

                @Override
                public void setFlagged(String messageId, boolean flagged) {}

                @Override
                public void move(String messageId, String targetFolderId) {}

                @Override
                public void delete(String messageId) {}

                @Override
                public void markSpam(String messageId) {}

                @Override
                public void markNotSpam(String messageId) {}

                @Override
                public String saveDraft(String draftJson) {
                    return "draft";
                }

                @Override
                public SendResult send(String payloadBase64) {
                    if (sendException != null) throw sendException;
                    return sendResult;
                }
            };
        }

        @Override
        public MessageSubmissionService submission() {
            return new MessageSubmissionService() {
                @Override
                public SendResult send(MessageDraft draft) {
                    if (sendException != null) throw sendException;
                    return sendResult;
                }

                @Override
                public void saveDraft(MessageDraft draft) {}
            };
        }

        @Override
        public com.graalmail.provider.api.AttachmentService attachments() {
            return null;
        }

        @Override
        public com.graalmail.provider.api.SearchService search() {
            return null;
        }

        @Override
        public com.graalmail.provider.api.SynchronizationService synchronization() {
            return new com.graalmail.provider.api.SynchronizationService() {
                @Override
                public com.graalmail.core.domain.SyncResult synchronize(String folderId, FolderSyncState syncState) {
                    return new com.graalmail.core.domain.SyncResult(folderId, 0, 0, 0, Instant.now(), List.of());
                }
            };
        }

        @Override
        public void close() {}
    }
}