/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

/** Synchronization state of a folder. */
public enum SyncState {
    /** Never synchronized. */
    NEVER,
    /** Synchronization in progress. */
    IN_PROGRESS,
    /** Successfully synchronized. */
    SYNCED,
    /** Synchronization failed. */
    FAILED,
    /** Synchronization failed due to UIDVALIDITY change. */
    UIDVALIDITY_CHANGED,
    /** Synchronization failed due to folder not found. */
    FOLDER_NOT_FOUND
}
