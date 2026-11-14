/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.util.List;

/** Mailbox and folder operations. */
public interface MailboxService {

    List<Folder> listFolders();

    /**
     * Creates a folder below {@code parentFolderId} (or at the root when
     * blank) and returns the created folder as the server sees it.
     *
     * @throws ProviderException with {@code FEATURE_UNSUPPORTED} when the
     *         provider cannot create folders
     */
    default Folder createFolder(String parentFolderId, String name) {
        throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                "Creating folders is not supported by this provider.", false);
    }

    /**
     * Renames a folder, keeping its parent.
     *
     * @throws ProviderException with {@code FEATURE_UNSUPPORTED} when the
     *         provider cannot rename folders
     */
    default void renameFolder(String folderId, String newName) {
        throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                "Renaming folders is not supported by this provider.", false);
    }

    /**
     * Deletes a folder including its subfolders and messages.
     *
     * @throws ProviderException with {@code FEATURE_UNSUPPORTED} when the
     *         provider cannot delete folders
     */
    default void deleteFolder(String folderId) {
        throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                "Deleting folders is not supported by this provider.", false);
    }

    /**
     * Removes every message from a folder (server side), for example when
     * emptying the trash.
     *
     * @throws ProviderException with {@code FEATURE_UNSUPPORTED} when the
     *         provider cannot empty folders
     */
    default void emptyFolder(String folderId) {
        throw new ProviderException(ErrorCode.FEATURE_UNSUPPORTED,
                "Emptying folders is not supported by this provider.", false);
    }

    /** Lists message headers for a folder, newest first. */
    MessageListPage listMessages(String folderId, int page, int pageSize);

    /** Lists messages by UID range (inclusive), newest first. */
    default MessageListPage listMessagesByUidRange(String folderId, long startUid, long endUid) {
        return new MessageListPage(List.of(), false, 0);
    }

    /** Refreshes the unread and total counters of a folder. */
    Folder refreshCounts(String folderId);

    /** The folder that acts as the inbox, when the provider has one. */
    default Folder inbox() {
        return listFolders().stream()
                .filter(f -> f.type() == com.graalmail.core.domain.FolderType.INBOX)
                .findFirst()
                .orElse(null);
    }
}
