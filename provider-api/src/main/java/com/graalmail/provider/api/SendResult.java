/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import java.time.Instant;

/**
 * Result of a send attempt. A result only means the message was accepted by
 * the submission service; it never guarantees final delivery.
 */
public final class SendResult {

    private final boolean accepted;
    private final String providerMessageId;
    private final boolean ambiguous;
    private final String warning;
    private final Instant acceptedAt;

    public SendResult(boolean accepted, String providerMessageId, boolean ambiguous, String warning,
                      Instant acceptedAt) {
        this.accepted = accepted;
        this.providerMessageId = providerMessageId == null ? "" : providerMessageId;
        this.ambiguous = ambiguous;
        this.warning = warning == null ? "" : warning;
        this.acceptedAt = acceptedAt == null ? Instant.now() : acceptedAt;
    }

    public static SendResult accepted(String providerMessageId) {
        return new SendResult(true, providerMessageId, false, "", Instant.now());
    }

    /** The server connection dropped after the command was issued; outcome unknown. */
    public static SendResult ambiguous(String warning) {
        return new SendResult(false, "", true, warning, Instant.now());
    }

    public boolean accepted() { return accepted; }
    public String providerMessageId() { return providerMessageId; }
    public boolean ambiguous() { return ambiguous; }
    public String warning() { return warning; }
    public Instant acceptedAt() { return acceptedAt; }
}
