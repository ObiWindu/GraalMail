/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.core.domain.FolderType;
import java.util.Locale;

/**
 * Maps the mailbox names exposed by Proton Mail Bridge onto the well known
 * folder types of the client.
 */
public final class ProtonFolderMapper {

    private ProtonFolderMapper() {
    }

    /** Maps a Bridge mailbox name onto a folder type. */
    public static FolderType map(String mailboxName) {
        if (mailboxName == null) {
            return FolderType.CUSTOM;
        }
        String name = mailboxName.trim().toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        switch (name) {
            case "inbox":
                return FolderType.INBOX;
            case "sent":
            case "sent mail":
            case "sent items":
                return FolderType.SENT;
            case "drafts":
            case "draft":
                return FolderType.DRAFTS;
            case "trash":
            case "deleted items":
            case "deleted":
                return FolderType.TRASH;
            case "spam":
            case "junk":
            case "quarantine":
                return FolderType.SPAM;
            case "archive":
            case "archived":
                return FolderType.ARCHIVE;
            case "outbox":
            case "sent-mail":
                return FolderType.OUTBOX;
            case "all mail":
            case "allmail":
                return FolderType.ALL;
            default:
                return FolderType.CUSTOM;
        }
    }

    /** True when the mailbox is one of the well known system folders. */
    public static boolean isSystem(String mailboxName) {
        return map(mailboxName) != FolderType.CUSTOM;
    }
}
