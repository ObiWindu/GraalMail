/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Outcome of one synchronization pass over a mailbox. */
public final class SyncResult {
    private final String folderId;
    private final int newMessages;
    private final int updatedMessages;
    private final int deletedMessages;
    private final Instant completedAt;
    private final List<String> errors;
    private final List<Long> newUids;
    private final List<Long> updatedUids;
    private final List<Long> deletedUids;
    private final long uidValidity;
    private final long uidNext;
    private final String highestModSeq;

    public SyncResult(String folderId, int newMessages, int updatedMessages, int deletedMessages,
                      Instant completedAt, List<String> errors) {
        this(folderId, newMessages, updatedMessages, deletedMessages, completedAt, errors,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                0, 0, "");
    }

    public SyncResult(String folderId, int newMessages, int updatedMessages, int deletedMessages,
                      Instant completedAt, List<String> errors,
                      List<Long> newUids, List<Long> updatedUids, List<Long> deletedUids,
                      long uidValidity, long uidNext, String highestModSeq) {
        this.folderId = folderId;
        this.newMessages = newMessages;
        this.updatedMessages = updatedMessages;
        this.deletedMessages = deletedMessages;
        this.completedAt = completedAt == null ? Instant.now() : completedAt;
        this.errors = new ArrayList<>(errors);
        this.newUids = new ArrayList<>(newUids);
        this.updatedUids = new ArrayList<>(updatedUids);
        this.deletedUids = new ArrayList<>(deletedUids);
        this.uidValidity = uidValidity;
        this.uidNext = uidNext;
        this.highestModSeq = highestModSeq == null ? "" : highestModSeq;
    }

    public String folderId() { return folderId; }
    public int newMessages() { return newMessages; }
    public int updatedMessages() { return updatedMessages; }
    public int deletedMessages() { return deletedMessages; }
    public Instant completedAt() { return completedAt; }
    public List<String> errors() { return List.copyOf(errors); }
    public boolean isSuccessful() { return errors.isEmpty(); }
    
    // New methods for UID-based sync
    public List<Long> newUids() { return List.copyOf(newUids); }
    public List<Long> updatedUids() { return List.copyOf(updatedUids); }
    public List<Long> deletedUids() { return List.copyOf(deletedUids); }
    public long uidValidity() { return uidValidity; }
    public long uidNext() { return uidNext; }
    public String highestModSeq() { return highestModSeq; }
    
    // Builder for new UID-based results
    public static SyncResult of(String folderId, List<Long> newUids, List<Long> updatedUids,
                                List<Long> deletedUids, long uidValidity, long uidNext,
                                String highestModSeq, List<String> errors) {
        return new SyncResult(folderId, newUids.size(), updatedUids.size(), deletedUids.size(),
                Instant.now(), errors, newUids, updatedUids, deletedUids,
                uidValidity, uidNext, highestModSeq);
    }
}
