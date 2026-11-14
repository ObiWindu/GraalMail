/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.filter;

import java.time.Instant;
import java.util.Objects;

/** A message hidden from the inbox until {@code wakeAt}. */
public final class SnoozedMessage {
    private final String messageId;
    private final String accountId;
    private final Instant wakeAt;
    private final Instant createdAt;

    public SnoozedMessage(String messageId, String accountId, Instant wakeAt, Instant createdAt) {
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.accountId = accountId == null ? "" : accountId;
        this.wakeAt = Objects.requireNonNull(wakeAt, "wakeAt");
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String messageId() { return messageId; }

    public String accountId() { return accountId; }

    public Instant wakeAt() { return wakeAt; }

    public Instant createdAt() { return createdAt; }

    public boolean isDue(Instant now) {
        return now != null && !now.isBefore(wakeAt);
    }
}
