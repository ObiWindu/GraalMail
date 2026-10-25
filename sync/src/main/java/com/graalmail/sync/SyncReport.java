/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Outcome of one synchronization run. */
public final class SyncReport {

    private final String accountId;
    private final int foldersSynced;
    private final int newMessages;
    private final int appliedActions;
    private final int failedActions;
    private final List<String> errors;
    private final Instant finishedAt;
    private final long durationMillis;

    public SyncReport(String accountId, int foldersSynced, int newMessages, int appliedActions,
                      int failedActions, List<String> errors, Instant finishedAt,
                      long durationMillis) {
        this.accountId = accountId;
        this.foldersSynced = foldersSynced;
        this.newMessages = newMessages;
        this.appliedActions = appliedActions;
        this.failedActions = failedActions;
        this.errors = List.copyOf(errors);
        this.finishedAt = finishedAt == null ? Instant.now() : finishedAt;
        this.durationMillis = durationMillis;
    }

    public String accountId() { return accountId; }
    public int foldersSynced() { return foldersSynced; }
    public int newMessages() { return newMessages; }
    public int appliedActions() { return appliedActions; }
    public int failedActions() { return failedActions; }
    public List<String> errors() { return errors; }
    public Instant finishedAt() { return finishedAt; }
    public long durationMillis() { return durationMillis; }
    public boolean successful() { return errors.isEmpty(); }
}
