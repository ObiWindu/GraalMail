/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.filter;

import java.time.Instant;
import java.util.Objects;

/** A reusable compose template (canned response). */
public final class MessageTemplate {
    private final String id;
    private final String accountId;
    private final String name;
    private final String subject;
    private final String bodyText;
    private final Instant updatedAt;

    public MessageTemplate(String id, String accountId, String name,
                           String subject, String bodyText, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = accountId == null ? "" : accountId;
        this.name = name == null ? "" : name;
        this.subject = subject == null ? "" : subject;
        this.bodyText = bodyText == null ? "" : bodyText;
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public String id() { return id; }

    public String accountId() { return accountId; }

    public String name() { return name; }

    public String subject() { return subject; }

    public String bodyText() { return bodyText; }

    public Instant updatedAt() { return updatedAt; }
}
