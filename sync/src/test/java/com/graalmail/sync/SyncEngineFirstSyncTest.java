/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SyncResult;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * First sync must populate the cache even when the provider reports the
 * messages with a zero "new" counter (Proton suppresses the notification
 * count on the first pass but still returns the UIDs).
 */
class SyncEngineFirstSyncTest {

    private MailStore store;
    private SyncEngine syncEngine;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
        store.saveAccount(Account.builder("a1", "test").email("test@example.com").build());
        store.saveFolders("a1",
                List.of(new Folder("inbox", "Inbox", "", FolderType.INBOX, 0, 0, true, false, "")));
        CredentialStore credentials = new InMemoryCredentialStore();
        AccountSessionFactory factory = new AccountSessionFactory(credentials) {
            @Override
            public ProviderSession open(Account account) {
                return new FirstSyncSession();
            }
        };
        syncEngine = new SyncEngine(store, factory);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void firstSyncWithZeroCounterButNonEmptyUidsStillSaves() {
        SyncReport report = syncEngine.syncAccount("a1");
        assertTrue(report.errors().isEmpty(), "errors: " + report.errors());
        assertFalse(store.message("m1").isEmpty(), "first-sync message must be cached");
    }

    private static final class FirstSyncSession implements ProviderSession {
        @Override
        public MailboxService mailboxes() {
            return new MailboxService() {
                @Override
                public List<Folder> listFolders() {
                    return List.of(
                            new Folder("inbox", "Inbox", "", FolderType.INBOX, 0, 0, true, false, ""));
                }

                @Override
                public MessageListPage listMessages(String folderId, int page, int pageSize) {
                    return new MessageListPage(List.of(), false, 0);
                }

                @Override
                public MessageListPage listMessagesByUidRange(String folderId, long startUid, long endUid) {
                    Message message = Message.builder("m1")
                            .accountId("a1")
                            .folderId("inbox")
                            .subject("First")
                            .bodyText("hello")
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
                public Message getMessage(String messageId, boolean includeBody) {
                    throw new UnsupportedOperationException("not needed");
                }

                @Override
                public void setRead(String messageId, boolean read) {
                }

                @Override
                public void setFlagged(String messageId, boolean flagged) {
                }

                @Override
                public void move(String messageId, String t) {
                }

                @Override
                public void delete(String messageId) {
                }

                @Override
                public void markSpam(String messageId) {
                }

                @Override
                public void markNotSpam(String messageId) {
                }

                @Override
                public String saveDraft(String draftJson) {
                    throw new UnsupportedOperationException("not needed");
                }

                @Override
                public com.graalmail.provider.api.SendResult send(String payloadBase64) {
                    throw new UnsupportedOperationException("not needed");
                }
            };
        }

        @Override
        public MessageSubmissionService submission() {
            return new MessageSubmissionService() {
                @Override
                public com.graalmail.provider.api.SendResult send(
                        com.graalmail.core.domain.MessageDraft draft) {
                    throw new UnsupportedOperationException("not needed");
                }

                @Override
                public void saveDraft(com.graalmail.core.domain.MessageDraft draft) {
                    throw new UnsupportedOperationException("not needed");
                }
            };
        }

        @Override
        public AttachmentService attachments() {
            return new AttachmentService() {
                @Override
                public java.io.InputStream open(
                        com.graalmail.core.domain.Attachment attachment) {
                    throw new UnsupportedOperationException("not needed");
                }

                @Override
                public void download(com.graalmail.core.domain.Attachment attachment,
                        java.nio.file.Path target) {
                    throw new UnsupportedOperationException("not needed");
                }
            };
        }

        @Override
        public SearchService search() {
            return query -> List.of();
        }

        @Override
        public SynchronizationService synchronization() {
            return new SynchronizationService() {
                @Override
                public SyncResult synchronize(String folderId, FolderSyncState syncState) {
                    // Proton-style first sync: UIDs present, notification counter suppressed.
                    return new SyncResult(folderId, 0, 0, 0, Instant.now(), List.of(),
                            List.of(101L), List.of(), List.of(), 1, 102, "");
                }

                @Override
                public boolean supportsIdle() {
                    return false;
                }
            };
        }

        @Override
        public void close() {
        }
    }
}
