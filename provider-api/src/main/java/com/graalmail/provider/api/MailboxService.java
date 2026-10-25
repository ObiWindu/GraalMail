/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import java.util.List;

/** Mailbox and folder operations. */
public interface MailboxService {

    List<Folder> listFolders();

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
