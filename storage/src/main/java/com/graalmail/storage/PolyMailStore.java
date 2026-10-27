package com.graalmail.storage;

import com.graalmail.p2p.blockchain.BlockchainCursor;
import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.mailbox.StorageReceipt;

import java.util.List;

public interface PolyMailStore {
    boolean notificationExists(String notificationId);
    void saveNotification(NotificationEvent event, String processingState);
    void updateNotificationState(String notificationId, String processingState);
    List<NotificationEvent> listNotifications();

    void enqueueOperation(String operationId, String messageId, String operationType, String state, long nextAttemptAt);
    List<OutgoingOperation> dueOperations(long now);
    void updateOperation(String operationId, String state, int retryCount, long nextAttemptAt, String lastErrorCode);

    void saveReceipt(StorageReceipt receipt);
    List<StorageReceipt> receiptsForMessage(String messageId);

    BlockchainCursor loadCursor(String chainId, String contractAddress);
    void saveCursor(BlockchainCursor cursor);

    boolean messageExists(String messageId);
    void saveMessage(String messageId, String mailboxIdentityId, String direction, String senderIdentity,
                     String recipientIdentity, long createdAt, long expiresAt, String contentHash,
                     String deliveryState, String verificationState, String readState, String lastErrorCode);
}
