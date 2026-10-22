/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.provider.api.MailboxService;
import com.graalmail.provider.api.MessageService;
import java.util.List;

/**
 * A {@link MessageService} that is backed by the Microsoft Graph mail API.
 */
public final class GraphBackedMessageService implements MessageService {

    private final MicrosoftGraphClient client;
    private final MailboxService mailboxes;
    private final String accountId;

    /**
     * Creates the service.
     *
     * @param client the Graph client
     * @param mailboxes the mailbox service used to resolve well known folders
     * @param accountId the account the messages belong to
     */
    public GraphBackedMessageService(MicrosoftGraphClient client, MailboxService mailboxes,
                                     String accountId) {
        this.client = client;
        this.mailboxes = mailboxes;
        this.accountId = accountId;
    }

    @Override
    public Message getMessage(String messageId, boolean includeBody) {
        MicrosoftGraphClient.GraphMessage message = client.getMessage(messageId);
        return GraphMessages.toMessage(accountId, "", message);
    }

    @Override
    public void setRead(String messageId, boolean read) {
        client.setFlags(messageId, read, null);
    }

    @Override
    public void setFlagged(String messageId, boolean flagged) {
        client.setFlags(messageId, null, flagged);
    }

    @Override
    public void move(String messageId, String targetFolderId) {
        client.move(messageId, targetFolderId);
    }

    @Override
    public void delete(String messageId) {
        client.delete(messageId);
    }

    @Override
    public void markSpam(String messageId) {
        String junk = folderId(FolderType.SPAM);
        if (junk != null) {
            client.move(messageId, junk);
        }
    }

    @Override
    public void markNotSpam(String messageId) {
        String inbox = folderId(FolderType.INBOX);
        if (inbox != null) {
            client.move(messageId, inbox);
        }
    }

    private String folderId(FolderType type) {
        List<Folder> folders = mailboxes.listFolders();
        return folders.stream()
                .filter(folder -> folder.type() == type)
                .map(Folder::id)
                .findFirst()
                .orElse(null);
    }
}
