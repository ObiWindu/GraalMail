/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import java.util.Objects;

/** Synchronization state of a folder used for incremental sync. */
public final class FolderSyncState {
    private final long uidValidity;
    private final long uidNext;
    private final long lastSyncAt;
    private final String highestModSeq;
    private final boolean neverSynced;

    public FolderSyncState(long uidValidity, long uidNext, long lastSyncAt,
                           String highestModSeq, boolean neverSynced) {
        this.uidValidity = uidValidity;
        this.uidNext = uidNext;
        this.lastSyncAt = lastSyncAt;
        this.highestModSeq = highestModSeq == null ? "" : highestModSeq;
        this.neverSynced = neverSynced;
    }

    public static FolderSyncState neverSynced() {
        return new FolderSyncState(0, 0, 0, "", true);
    }

    public long uidValidity() { return uidValidity; }
    public long uidNext() { return uidNext; }
    public long lastSyncAt() { return lastSyncAt; }
    public String highestModSeq() { return highestModSeq; }
    public boolean isNeverSynced() { return neverSynced; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FolderSyncState that)) return false;
        return uidValidity == that.uidValidity && uidNext == that.uidNext
                && lastSyncAt == that.lastSyncAt && neverSynced == that.neverSynced
                && Objects.equals(highestModSeq, that.highestModSeq);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uidValidity, uidNext, lastSyncAt, highestModSeq, neverSynced);
    }
}
