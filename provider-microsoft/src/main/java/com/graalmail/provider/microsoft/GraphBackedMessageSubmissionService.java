/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.MessageDraft;
import com.graalmail.provider.api.MessageSubmissionService;
import com.graalmail.provider.api.SendResult;

/**
 * A {@link MessageSubmissionService} that is backed by the Microsoft Graph
 * {@code /me/sendMail} endpoint.
 */
public final class GraphBackedMessageSubmissionService implements MessageSubmissionService {

    private final MicrosoftGraphClient client;
    private final String from;

    /**
     * Creates the service.
     *
     * @param client the Graph client
     * @param from the address the messages are sent from
     */
    public GraphBackedMessageSubmissionService(MicrosoftGraphClient client, String from) {
        this.client = client;
        this.from = from;
    }

    @Override
    public SendResult send(MessageDraft draft) {
        if (!draft.hasRecipients()) {
            throw new com.graalmail.core.error.ProviderException(
                    com.graalmail.core.error.ErrorCode.INVALID_RECIPIENT,
                    "The message has no recipient.", false);
        }
        String id = client.sendMessage(draft, from);
        return SendResult.accepted(id);
    }

    @Override
    public void saveDraft(MessageDraft draft) {
        client.createDraft(draft, from);
    }
}
