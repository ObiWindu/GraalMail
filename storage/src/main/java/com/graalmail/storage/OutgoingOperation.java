package com.graalmail.storage;

public record OutgoingOperation(
        String operationId,
        String messageId,
        String operationType,
        String state,
        int retryCount,
        long nextAttemptAt,
        String lastErrorCode,
        long createdAt,
        long updatedAt
) {}