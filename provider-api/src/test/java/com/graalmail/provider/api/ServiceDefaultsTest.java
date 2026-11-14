/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The optional service operations must fail with a clear
 * {@code FEATURE_UNSUPPORTED} error on providers that do not implement them,
 * so the UI can hide or explain them instead of crashing.
 */
class ServiceDefaultsTest {

    private static final MailboxService MAILBOXES = new MailboxService() {
        @Override
        public List<Folder> listFolders() {
            return List.of();
        }

        @Override
        public MessageListPage listMessages(String folderId, int page, int pageSize) {
            return new MessageListPage(List.of(), false, 0);
        }

        @Override
        public Folder refreshCounts(String folderId) {
            return null;
        }
    };

    private static final MessageService MESSAGES = new MessageService() {
        @Override
        public Message getMessage(String messageId, boolean includeBody) {
            return null;
        }

        @Override
        public void setRead(String messageId, boolean read) { }

        @Override
        public void setFlagged(String messageId, boolean flagged) { }

        @Override
        public void move(String messageId, String targetFolderId) { }

        @Override
        public void delete(String messageId) { }

        @Override
        public void markSpam(String messageId) { }

        @Override
        public void markNotSpam(String messageId) { }

        @Override
        public String saveDraft(String draftJson) {
            return "";
        }

        @Override
        public SendResult send(String payloadBase64) {
            return SendResult.ambiguous("not implemented");
        }
    };

    @Test
    void folderManagementDefaultsToUnsupported() {
        assertUnsupported(() -> MAILBOXES.createFolder(null, "X"));
        assertUnsupported(() -> MAILBOXES.renameFolder("INBOX", "X"));
        assertUnsupported(() -> MAILBOXES.deleteFolder("INBOX"));
        assertUnsupported(() -> MAILBOXES.emptyFolder("INBOX"));
    }

    @Test
    void rawMessageExportDefaultsToUnsupported() {
        assertUnsupported(() -> MESSAGES.rawMessage("INBOX|1"));
    }

    private static void assertUnsupported(Runnable operation) {
        ProviderException e = assertThrows(ProviderException.class, operation::run);
        assertEquals(ErrorCode.FEATURE_UNSUPPORTED, e.code());
    }
}
