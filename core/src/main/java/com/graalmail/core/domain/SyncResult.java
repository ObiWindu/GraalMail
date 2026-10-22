/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Outcome of one synchronization pass over a mailbox. */
public final class SyncResult {
    private final String folderId;
    private final int newMessages;
    private final int updatedMessages;
    private final int deletedMessages;
    private final Instant completedAt;
    private final List<String> errors;

    public SyncResult(String folderId, int newMessages, int updatedMessages, int deletedMessages,
                      Instant completedAt, List<String> errors) {
        this.folderId = folderId;
        this.newMessages = newMessages;
        this.updatedMessages = updatedMessages;
        this.deletedMessages = deletedMessages;
        this.completedAt = completedAt == null ? Instant.now() : completedAt;
        this.errors = new ArrayList<>(errors);
    }

    public String folderId() { return folderId; }
    public int newMessages() { return newMessages; }
    public int updatedMessages() { return updatedMessages; }
    public int deletedMessages() { return deletedMessages; }
    public Instant completedAt() { return completedAt; }
    public List<String> errors() { return List.copyOf(errors); }
    public boolean isSuccessful() { return errors.isEmpty(); }
}
