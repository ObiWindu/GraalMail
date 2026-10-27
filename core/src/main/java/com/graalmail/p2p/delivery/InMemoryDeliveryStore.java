package com.graalmail.p2p.delivery;

import com.graalmail.p2p.mailbox.StorageReceipt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Non-durable store for tests and ephemeral nodes. */
public class InMemoryDeliveryStore implements DeliveryStore {
    private final Map<String, OutgoingEntry> outgoing = new ConcurrentHashMap<>();
    private final Map<String, MessageRecord> messages = new ConcurrentHashMap<>();
    private final List<StorageReceipt> receipts = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void saveOutgoing(OutgoingEntry entry) {
        outgoing.putIfAbsent(entry.operationId(), entry);
    }

    @Override
    public void updateOutgoing(String operationId, String state, int retryCount, long nextAttemptAt, String lastError) {
        outgoing.computeIfPresent(operationId, (k, e) -> new OutgoingEntry(
                e.operationId(), e.messageId(), e.operationType(), state, retryCount, nextAttemptAt, lastError, e.payload()));
    }

    @Override
    public List<OutgoingEntry> pendingOutgoing() {
        List<OutgoingEntry> out = new ArrayList<>();
        for (OutgoingEntry e : outgoing.values()) {
            if (!e.isTerminal()) out.add(e);
        }
        return out;
    }

    @Override
    public void saveMessageRecord(MessageRecord record) {
        messages.putIfAbsent(record.messageId(), record);
    }

    @Override
    public void updateMessageState(String messageId, String deliveryState, String verificationState, String lastError) {
        messages.computeIfPresent(messageId, (k, m) -> new MessageRecord(
                m.messageId(), m.direction(), m.senderIdentity(), m.recipientIdentity(), m.createdAt(),
                m.expiresAt(), m.contentHashHex(), m.notificationTxHash(), deliveryState, verificationState, lastError));
    }

    @Override
    public void updateMessageNotification(String messageId, String txHash) {
        messages.computeIfPresent(messageId, (k, m) -> new MessageRecord(
                m.messageId(), m.direction(), m.senderIdentity(), m.recipientIdentity(), m.createdAt(),
                m.expiresAt(), m.contentHashHex(), txHash, m.deliveryState(), m.verificationState(), m.lastError()));
    }

    @Override
    public boolean hasMessageRecord(String messageId) { return messages.containsKey(messageId); }

    @Override
    public MessageRecord messageRecord(String messageId) { return messages.get(messageId); }

    @Override
    public void saveReceiptRecord(StorageReceipt receipt, boolean verified) {
        receipts.add(receipt);
    }

    public List<StorageReceipt> receipts() { return List.copyOf(receipts); }

    public List<MessageRecord> messageRecords() { return List.copyOf(messages.values()); }
}
