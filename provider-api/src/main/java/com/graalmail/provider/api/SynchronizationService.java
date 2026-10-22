/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.SyncResult;

/** Mailbox synchronization. */
public interface SynchronizationService {

    /** Synchronizes a single folder. */
    SyncResult synchronize(String folderId);

    /** Synchronizes all folders that the provider marks as synchronizable. */
    default java.util.List<SyncResult> synchronizeAll() {
        return java.util.List.of();
    }

    /** True when the provider can push changes with IMAP IDLE or a native channel. */
    default boolean supportsIdle() {
        return false;
    }
}
