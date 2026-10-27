package com.graalmail.p2p.delivery;

/** One durable outgoing operation of the send state machine (spec sections 10, 13.3). */
public record OutgoingEntry(
        String operationId,
        String messageId,
        String operationType,
        String state,
        int retryCount,
        long nextAttemptAt,
        String lastError,
        byte[] payload
) {
    /** Terminal states need no further processing. */
    public boolean isTerminal() {
        return "NOTIFICATION_CONFIRMED".equals(state) || "FAILED".equals(state) || "EXPIRED".equals(state);
    }
}
