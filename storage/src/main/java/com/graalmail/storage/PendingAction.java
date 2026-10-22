/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import java.time.Instant;

/**
 * An operation that was requested while offline or that failed and must be
 * retried. Actions stay pending until the server confirms them.
 */
public final class PendingAction {

    public enum Type {
        MARK_READ,
        MARK_UNREAD,
        FLAG,
        UNFLAG,
        MOVE,
        DELETE,
        MARK_SPAM,
        MARK_NOT_SPAM,
        SAVE_DRAFT,
        SEND
    }

    /**
     * Send state machine for email submission.
     * DRAFT -> QUEUED -> SUBMITTING -> ACCEPTED | FAILED_BEFORE_SUBMISSION | SUBMISSION_OUTCOME_UNKNOWN
     */
    public enum SendState {
        DRAFT,
        QUEUED,
        SUBMITTING,
        ACCEPTED,
        FAILED_BEFORE_SUBMISSION,
        SUBMISSION_OUTCOME_UNKNOWN
    }

    public enum State {
        PENDING,
        IN_PROGRESS,
        DONE,
        FAILED
    }

    private final String id;
    private final String accountId;
    private final Type type;
    private final String targetId;
    private final String targetFolderId;
    private final String payload;
    private final State state;
    private final int attempts;
    private final String lastError;
    private final Instant createdAt;
    private final SendState sendState;

    public PendingAction(String id, String accountId, Type type, String targetId, String targetFolderId,
                         String payload, State state, int attempts, String lastError, Instant createdAt,
                         SendState sendState) {
        this.id = id;
        this.accountId = accountId;
        this.type = type;
        this.targetId = targetId == null ? "" : targetId;
        this.targetFolderId = targetFolderId == null ? "" : targetFolderId;
        this.payload = payload == null ? "" : payload;
        this.state = state;
        this.attempts = attempts;
        this.lastError = lastError == null ? "" : lastError;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.sendState = sendState;
    }

    public PendingAction(String id, String accountId, Type type, String targetId, String targetFolderId,
                         String payload, State state, int attempts, String lastError, Instant createdAt) {
        this(id, accountId, type, targetId, targetFolderId, payload, state, attempts, lastError, createdAt,
                type == Type.SEND ? SendState.QUEUED : null);
    }

    public static PendingAction of(String id, String accountId, Type type, String targetId) {
        return new PendingAction(id, accountId, type, targetId, "", "", State.PENDING, 0, "", Instant.now(),
                type == Type.SEND ? SendState.QUEUED : null);
    }

    public static PendingAction of(String id, String accountId, Type type, String targetId, String payload) {
        return new PendingAction(id, accountId, type, targetId, "", payload, State.PENDING, 0, "", Instant.now(),
                type == Type.SEND ? SendState.QUEUED : null);
    }

    public String id() { return id; }
    public String accountId() { return accountId; }
    public Type type() { return type; }
    public String targetId() { return targetId; }
    public String targetFolderId() { return targetFolderId; }
    public String payload() { return payload; }
    public State state() { return state; }
    public int attempts() { return attempts; }
    public String lastError() { return lastError; }
    public Instant createdAt() { return createdAt; }
    public SendState sendState() { return sendState; }

    public PendingAction withState(State newState, int newAttempts, String error) {
        return new PendingAction(id, accountId, type, targetId, targetFolderId, payload, newState,
                newAttempts, error, createdAt, sendState);
    }

    public PendingAction withSendState(SendState newSendState) {
        return new PendingAction(id, accountId, type, targetId, targetFolderId, payload, state,
                attempts, lastError, createdAt, newSendState);
    }
}
