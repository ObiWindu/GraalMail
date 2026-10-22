/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

/** Well-known mailbox roles. Providers map their native folders onto these. */
public enum FolderType {
    INBOX,
    SENT,
    DRAFTS,
    TRASH,
    SPAM,
    ARCHIVE,
    OUTBOX,
    ALL,
    CUSTOM;

    public boolean isSystem() {
        return this != CUSTOM;
    }
}
