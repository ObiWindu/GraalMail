/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SyncResult;
import com.graalmail.core.filter.FilterAction;
import com.graalmail.core.filter.FilterCondition;
import com.graalmail.core.filter.MessageFilter;
import com.graalmail.provider.api.AttachmentService;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.ProviderSession;
import com.graalmail.provider.api.SearchService;
import com.graalmail.provider.api.SynchronizationService;
import com.graalmail.security.store.CredentialStore;
import com.graalmail.security.store.InMemoryCredentialStore;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.MailStore;
import com.graalmail.storage.SqlMailStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Incoming mail that matches a filter is filed automatically during sync. */
class SyncEngineFilterTest {

    private MailStore store;
    private SyncEngine syncEngine;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        store.saveAccount(Account.builder("a1", "test").email("test@example.com").build());
        store.saveFolders("a1", List.of(
                new Folder("inbox", "Inbox", "", FolderType.INBOX, 0, 0, true, false, ""),
                new Folder("archive", "Archive", "", FolderType.ARCHIVE, 0, 0, true, false, "")));
        store.saveFilter(new MessageFilter("f1", "a1", "Billing", true, false, 1,
                List.of(new FilterCondition(FilterCondition.Field.FROM,
                        FilterCondition.Operator.CONTAINS, "billing@example.com")),
                List.of(FilterAction.markRead(), FilterAction.moveTo("archive")),
                Instant.now()));
        CredentialStore credentials = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(credentials) {
            @Override
            public ProviderSession open(Account account) {
                return new BillingSession();
            }
        };
        syncEngine = new SyncEngine(store, factory);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void filterMovesAndMarksNewMessage() {
        SyncReport report = syncEngine.syncAccount("a1");
        assertTrue(report.errors().isEmpty(), "errors: " + report.errors());
        Message cached = store.message("m-bill").orElseThrow();
        assertEquals("archive", cached.folderId());
        assertFalse(cached.unread());
        assertFalse(store.pendingActions("a1").isEmpty());
    }

    private static final class BillingSession implements ProviderSession {
        @Override
        public MailboxService mailboxes() {
            return new MailboxService() {
                @Override
                public List<Folder> listFolders() {
                    return List.of(
                            new Folder("inbox", "Inbox", "", FolderType.INBOX, 0, 0, true, false, ""),
                            new Folder("archive", "Archive", "", FolderType.ARCHIVE, 0, 0, true, false, ""));
                }

                @Override
                public MessageListPage listMessages(String folderId, int page, int pageSize) {
                    return new MessageListPage(List.of(), false, 0);
                }

                @Override
                public MessageListPage listMessagesByUidRange(String folderId, long startUid, long endUid) {
                    if (!"inbox".equals(folderId)) {
                        return new MessageListPage(List.of(), false, 0);
                    }
                    Message message = Message.builder("m-bill")
                            .accountId("a1")
                            .folderId("inbox")
                            .subject("Your invoice")
                            .from(new Address("Billing", "billing@example.com"))
                            .unread(true)
                            .date(Instant.now())
                            .build();
                    return new MessageListPage(List.of(message), false, 1);
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
                public Message getMessage(String id, boolean includeBody) {
                    throw new UnsupportedOperationException("not needed");
                }

                @Override
                public void setRead(String id, boolean read) {
                }

                @Override
                public void setFlagged(String id, boolean flagged) {
                }

                @Override
                public void move(String id, String target) {
                }

                @Override
                public void delete(String id) {
                }

                @Override
                public void markSpam(String id) {
                }

                @Override
                public void markNotSpam(String id) {
                }

                @Override
                public String saveDraft(String draftJson) {
                    return "d1";
                }

                @Override
                public com.graalmail.provider.api.SendResult send(String payload) {
                    throw new UnsupportedOperationException("not needed");
                }
            };
        }

        @Override
        public MessageSubmissionService submission() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public AttachmentService attachments() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public SearchService search() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public SynchronizationService synchronization() {
            return (folderId, state) -> SyncResult.of(folderId,
                    "inbox".equals(folderId) ? List.of(1L) : List.of(),
                    List.of(), List.of(), 1L, 2L, "", List.of());
        }

        @Override
        public void close() {
        }
    }
}
