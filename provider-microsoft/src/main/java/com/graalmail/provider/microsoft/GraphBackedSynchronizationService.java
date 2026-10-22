/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.SyncResult;
import com.graalmail.provider.api.FolderSyncState;
import com.graalmail.provider.api.SynchronizationService;
import java.time.Instant;
import java.util.List;

/**
 * A {@link SynchronizationService} that counts the messages of a folder
 * through the Microsoft Graph API.
 */
public final class GraphBackedSynchronizationService implements SynchronizationService {

    private final MicrosoftGraphClient client;

    /**
     * Creates the service.
     *
     * @param client the Graph client
     */
    public GraphBackedSynchronizationService(MicrosoftGraphClient client) {
        this.client = client;
    }

    @Override
    public SyncResult synchronize(String folderId, FolderSyncState syncState) {
        int count = client.listMessages(folderId, 0, 1).size();
        return new SyncResult(folderId, count, 0, 0, Instant.now(), List.of());
    }

    @Override
    public boolean supportsIdle() {
        return true;
    }
}
