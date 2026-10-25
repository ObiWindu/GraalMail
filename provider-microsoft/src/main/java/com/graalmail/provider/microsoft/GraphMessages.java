/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.domain.Message;

/** Converts Graph messages into the provider neutral message model. */
final class GraphMessages {

    private GraphMessages() {
    }

    /**
     * Converts a Graph message.
     *
     * @param accountId the account the message belongs to
     * @param folderId the folder the message lives in
     * @param message the Graph message
     * @return the converted message
     */
    static Message toMessage(String accountId, String folderId,
                             MicrosoftGraphClient.GraphMessage message) {
        return Message.builder(message.id())
                .accountId(accountId)
                .folderId(folderId)
                .internetMessageId(message.internetMessageId())
                .subject(message.subject())
                .from(message.from())
                .to(message.to())
                .date(message.received())
                .snippet(message.bodyPreview())
                .unread(!message.read())
                .flagged(message.flagged())
                .attachment(message.hasAttachments())
                .size(message.size())
                .bodyText(message.bodyText())
                .bodyHtml(message.bodyHtml())
                .build();
    }
}
