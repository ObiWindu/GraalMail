/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.util.Objects;

/** A locally saved draft for autosave/recovery. */
public final class LocalDraft {
    private final String id;
    private final String accountId;
    private final String folderId;
    private final String draftJson;
    private final long updatedAt;
    private final boolean recovered;

    public LocalDraft(String id, String accountId, String folderId, String draftJson, long updatedAt, boolean recovered) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.folderId = folderId == null ? "" : folderId;
        this.draftJson = Objects.requireNonNull(draftJson, "draftJson");
        this.updatedAt = updatedAt;
        this.recovered = recovered;
    }

    public static LocalDraft of(String accountId, String folderId, String draftJson) {
        return new LocalDraft(java.util.UUID.randomUUID().toString(), accountId, folderId, draftJson, System.currentTimeMillis(), false);
    }

    public String id() { return id; }
    public String accountId() { return accountId; }
    public String folderId() { return folderId; }
    public String draftJson() { return draftJson; }
    public long updatedAt() { return updatedAt; }
    public boolean recovered() { return recovered; }

    public LocalDraft withRecovered(boolean recovered) {
        return new LocalDraft(id, accountId, folderId, draftJson, updatedAt, recovered);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LocalDraft d && d.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
