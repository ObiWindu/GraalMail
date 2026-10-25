/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.provider.api.MailboxService;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link MailboxService} that is backed by the Microsoft Graph mail API.
 */
public final class GraphBackedMailboxService implements MailboxService {

    private final MicrosoftGraphClient client;
    private final String accountId;

    /**
     * Creates the service.
     *
     * @param client the Graph client
     * @param accountId the account the folders belong to
     */
    public GraphBackedMailboxService(MicrosoftGraphClient client, String accountId) {
        this.client = client;
        this.accountId = accountId;
    }

    @Override
    public List<Folder> listFolders() {
        List<Folder> folders = new ArrayList<>();
        for (MicrosoftGraphClient.GraphFolder graphFolder : client.listMailFolders()) {
            FolderType type = graphFolder.wellKnownName().isBlank()
                    ? MicrosoftFolderMapping.typeOf(graphFolder.displayName())
                    : MicrosoftFolderMapping.typeOfGraphId(graphFolder.wellKnownName());
            folders.add(new Folder(graphFolder.id(),
                    graphFolder.displayName().isBlank() ? graphFolder.id() : graphFolder.displayName(),
                    "", type, graphFolder.totalItemCount(), graphFolder.unreadItemCount(), true,
                    false, graphFolder.wellKnownName()));
        }
        return folders;
    }

    @Override
    public MessageListPage listMessages(String folderId, int page, int pageSize) {
        List<Message> messages = new ArrayList<>();
        for (MicrosoftGraphClient.GraphMessage graphMessage
                : client.listMessages(folderId, page, pageSize)) {
            messages.add(GraphMessages.toMessage(accountId, folderId, graphMessage));
        }
        return new MessageListPage(messages, messages.size() >= pageSize, messages.size());
    }

    @Override
    public Folder refreshCounts(String folderId) {
        for (Folder folder : listFolders()) {
            if (folder.id().equals(folderId)) {
                return folder;
            }
        }
        return Folder.of(folderId, folderId, FolderType.CUSTOM);
    }
}
