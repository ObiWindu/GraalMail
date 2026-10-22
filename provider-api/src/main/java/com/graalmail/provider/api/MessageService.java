/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.Message;

/** Operations on individual messages. */
public interface MessageService {

    Message getMessage(String messageId, boolean includeBody);

    void setRead(String messageId, boolean read);

    void setFlagged(String messageId, boolean flagged);

    /** Moves a message to another folder; returns the server state when confirmed. */
    void move(String messageId, String targetFolderId);

    void delete(String messageId);

    void markSpam(String messageId);

    void markNotSpam(String messageId);
}
