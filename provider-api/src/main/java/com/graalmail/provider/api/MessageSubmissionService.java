/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import com.graalmail.core.domain.MessageDraft;

/** Composing and sending. */
public interface MessageSubmissionService {

    /**
     * Submits a message. The returned result reports acceptance by the
     * submission service, not final delivery.
     */
    SendResult send(MessageDraft draft);

    /** Saves a draft, either locally or on the server when the provider supports it. */
    void saveDraft(MessageDraft draft);
}
