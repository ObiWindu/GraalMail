package com.graalmail.p2p.delivery;

import com.graalmail.p2p.mailbox.StorageReceipt;

import java.util.List;

/**
 * Durable delivery state for the send/receive state machines (spec sections 10, 11, 13).
 * Core owns the interface; SQLite and in-memory implementations live in their modules.
 * All operations must be safe to call repeatedly (spec section 7.4).
 */
public interface DeliveryStore {
    void saveOutgoing(OutgoingEntry entry);
    void updateOutgoing(String operationId, String state, int retryCount, long nextAttemptAt, String lastError);
    List<OutgoingEntry> pendingOutgoing();

    void saveMessageRecord(MessageRecord record);
    void updateMessageState(String messageId, String deliveryState, String verificationState, String lastError);
    void updateMessageNotification(String messageId, String txHash);
    boolean hasMessageRecord(String messageId);
    MessageRecord messageRecord(String messageId);

    void saveReceiptRecord(StorageReceipt receipt, boolean verified);
}
