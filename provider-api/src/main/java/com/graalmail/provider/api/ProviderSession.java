/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

/**
 * An open connection to a mail provider. Sessions are not thread safe and must
 * be closed by the caller.
 */
public interface ProviderSession extends AutoCloseable {

    MailboxService mailboxes();

    MessageService messages();

    MessageSubmissionService submission();

    AttachmentService attachments();

    SearchService search();

    SynchronizationService synchronization();

    @Override
    void close();
}
