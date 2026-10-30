package com.graalmail.storage;

import com.graalmail.p2p.blockchain.BlockchainCursor;
import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.mailbox.StorageReceipt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqlPolyMailStore implements PolyMailStore, com.graalmail.p2p.blockchain.CursorStore,
        com.graalmail.p2p.delivery.DeliveryStore {
    private final SqlDatabase db;

    public SqlPolyMailStore(SqlDatabase db) { this.db = db; }

    private static String nid(NotificationEvent e) {
        return e.chainId() + ":" + e.transactionHash() + ":" + e.logIndex();
    }

    @Override
    public boolean notificationExists(String notificationId) {
        List<Map<String, Object>> rows = db.query("SELECT notification_id FROM polymail_notifications WHERE notification_id = ?", notificationId);
        return !rows.isEmpty();
    }

    @Override
    public void saveNotification(NotificationEvent event, String processingState) {
        String id = nid(event);
        if (notificationExists(id)) return;
        db.update("INSERT INTO polymail_notifications (notification_id, chain_id, contract_address, transaction_hash, log_index, block_number, block_hash, message_id, content_hash, inbox_tag, expires_at, processing_state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, event.chainId(), event.contractAddress(), event.transactionHash(), event.logIndex(), event.blockNumber(),
                event.blockHash(), event.messageId(), bytesToHex(event.contentHash()), bytesToHex(event.inboxTag()), event.expiresAt(), processingState);
    }

    @Override
    public void updateNotificationState(String notificationId, String processingState) {
        db.update("UPDATE polymail_notifications SET processing_state = ? WHERE notification_id = ?", processingState, notificationId);
    }

    @Override
    public List<NotificationEvent> listNotifications() {
        List<Map<String, Object>> rows = db.query("SELECT * FROM polymail_notifications");
        List<NotificationEvent> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new NotificationEvent(
                    str(r.get("chain_id")), str(r.get("contract_address")), str(r.get("transaction_hash")),
                    ((Number) r.get("log_index")).intValue(), ((Number) r.get("block_number")).longValue(), str(r.get("block_hash")),
                    str(r.get("message_id")), hexToBytes(str(r.get("content_hash"))), hexToBytes(str(r.get("inbox_tag"))),
                    ((Number) r.get("expires_at")).longValue(), System.currentTimeMillis()));
        }
        return out;
    }

    @Override
    public void enqueueOperation(String operationId, String messageId, String operationType, String state, long nextAttemptAt) {
        long now = System.currentTimeMillis();
        db.update("INSERT INTO polymail_outgoing_queue (operation_id, message_id, operation_type, current_state, retry_count, next_attempt_at, last_error_code, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                operationId, messageId, operationType, state, 0, nextAttemptAt, "", now, now);
    }

    @Override
    public List<OutgoingOperation> dueOperations(long now) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM polymail_outgoing_queue WHERE next_attempt_at <= ?", now);
        List<OutgoingOperation> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new OutgoingOperation(
                    str(r.get("operation_id")), str(r.get("message_id")), str(r.get("operation_type")), str(r.get("current_state")),
                    ((Number) r.get("retry_count")).intValue(), ((Number) r.get("next_attempt_at")).longValue(),
                    str(r.get("last_error_code")), ((Number) r.get("created_at")).longValue(), ((Number) r.get("updated_at")).longValue()));
        }
        return out;
    }

    @Override
    public void updateOperation(String operationId, String state, int retryCount, long nextAttemptAt, String lastErrorCode) {
        db.update("UPDATE polymail_outgoing_queue SET current_state=?, retry_count=?, next_attempt_at=?, last_error_code=?, updated_at=? WHERE operation_id=?",
                state, retryCount, nextAttemptAt, lastErrorCode, System.currentTimeMillis(), operationId);
    }

    @Override
    public void saveReceipt(StorageReceipt receipt) {
        saveReceiptRecord(receipt, false);
    }

    @Override
    public void saveReceiptRecord(StorageReceipt receipt, boolean verified) {
        db.update("INSERT INTO polymail_storage_receipts (receipt_id, message_id, content_hash, peer_id, receipt_payload, receipt_signature, accepted_at, retention_until, verification_state) VALUES (?,?,?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), receipt.messageId(), receipt.contentId(), receipt.peerId(),
                "", receipt.signature(), receipt.acceptedAt(), receipt.retentionUntil(),
                verified ? "VERIFIED" : "UNVERIFIED");
    }

    @Override
    public List<StorageReceipt> receiptsForMessage(String messageId) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM polymail_storage_receipts WHERE message_id = ?", messageId);
        List<StorageReceipt> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            byte[] sig = blob(r.get("receipt_signature"));
            if (sig == null) sig = new byte[0];
            String protocolVersion = str(r.get("protocol_version"));
            if (protocolVersion.isBlank()) protocolVersion = "GPMP/1";
            out.add(new StorageReceipt(str(r.get("content_hash")), str(r.get("message_id")), str(r.get("peer_id")),
                    ((Number) r.get("accepted_at")).longValue(), ((Number) r.get("retention_until")).longValue(),
                    protocolVersion, sig));
        }
        return out;
    }

    @Override
    public BlockchainCursor loadCursor(String chainId, String contractAddress) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM polymail_blockchain_cursor WHERE chain_id = ? AND contract_address = ?", chainId, contractAddress);
        if (rows.isEmpty()) return new BlockchainCursor(chainId, contractAddress, 0, "");
        Map<String, Object> r = rows.get(0);
        return new BlockchainCursor(str(r.get("chain_id")), str(r.get("contract_address")), ((Number) r.get("last_block")).longValue(), str(r.get("block_hash")));
    }

    @Override
    public void saveCursor(BlockchainCursor cursor) {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM polymail_blockchain_cursor WHERE chain_id = ? AND contract_address = ?",
                cursor.chainId(), cursor.contractAddress());
        if (rows.isEmpty()) {
            db.update("INSERT INTO polymail_blockchain_cursor (chain_id, contract_address, last_block, block_hash) VALUES (?,?,?,?)",
                    cursor.chainId(), cursor.contractAddress(), cursor.lastBlock(), cursor.blockHash());
        } else {
            db.update("UPDATE polymail_blockchain_cursor SET last_block=?, block_hash=? WHERE chain_id=? AND contract_address=?",
                    cursor.lastBlock(), cursor.blockHash(), cursor.chainId(), cursor.contractAddress());
        }
    }

    @Override
    public BlockchainCursor load(String chainId, String contractAddress) {
        return loadCursor(chainId, contractAddress);
    }

    @Override
    public void save(BlockchainCursor cursor) {
        saveCursor(cursor);
    }

    @Override
    public boolean messageExists(String messageId) {
        List<Map<String, Object>> rows = db.query("SELECT message_id FROM polymail_messages WHERE message_id = ?", messageId);
        return !rows.isEmpty();
    }

    @Override
    public void saveMessage(String messageId, String mailboxIdentityId, String direction, String senderIdentity,
                           String recipientIdentity, long createdAt, long expiresAt, String contentHash,
                           String deliveryState, String verificationState, String readState, String lastErrorCode) {
        if (messageExists(messageId)) return;
        db.update("INSERT INTO polymail_messages (message_id, mailbox_identity_id, direction, sender_identity, recipient_identity, created_at, expires_at, content_hash, delivery_state, verification_state, read_state, last_error_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                messageId, mailboxIdentityId, direction, senderIdentity, recipientIdentity, createdAt, expiresAt, contentHash,
                deliveryState, verificationState, readState, lastErrorCode);
    }

    @Override
    public void saveOutgoing(com.graalmail.p2p.delivery.OutgoingEntry entry) {
        List<Map<String, Object>> rows = db.query("SELECT operation_id FROM polymail_outgoing_queue WHERE operation_id = ?", entry.operationId());
        if (!rows.isEmpty()) return;
        long now = System.currentTimeMillis();
        db.update("INSERT INTO polymail_outgoing_queue (operation_id, message_id, operation_type, current_state, retry_count, next_attempt_at, last_error_code, created_at, updated_at, payload) VALUES (?,?,?,?,?,?,?,?,?,?)",
                entry.operationId(), entry.messageId(), entry.operationType(), entry.state(), entry.retryCount(),
                entry.nextAttemptAt(), entry.lastError(), now, now, entry.payload());
    }

    @Override
    public void updateOutgoing(String operationId, String state, int retryCount, long nextAttemptAt, String lastError) {
        updateOperation(operationId, state, retryCount, nextAttemptAt, lastError);
    }

    @Override
    public List<com.graalmail.p2p.delivery.OutgoingEntry> pendingOutgoing() {
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM polymail_outgoing_queue WHERE current_state NOT IN ('NOTIFICATION_CONFIRMED','FAILED','EXPIRED')");
        List<com.graalmail.p2p.delivery.OutgoingEntry> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new com.graalmail.p2p.delivery.OutgoingEntry(
                    str(r.get("operation_id")), str(r.get("message_id")), str(r.get("operation_type")),
                    str(r.get("current_state")), ((Number) r.get("retry_count")).intValue(),
                    ((Number) r.get("next_attempt_at")).longValue(), str(r.get("last_error_code")),
                    blob(r.get("payload"))));
        }
        return out;
    }

    @Override
    public void saveMessageRecord(com.graalmail.p2p.delivery.MessageRecord record) {
        if (messageExists(record.messageId())) return;
        db.update("INSERT INTO polymail_messages (message_id, mailbox_identity_id, direction, sender_identity, recipient_identity, created_at, expires_at, content_hash, notification_transaction_hash, delivery_state, verification_state, read_state, last_error_code) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                record.messageId(), "", record.direction(), record.senderIdentity(), record.recipientIdentity(),
                record.createdAt(), record.expiresAt(), record.contentHashHex(), record.notificationTxHash(),
                record.deliveryState(), record.verificationState(), "UNREAD", record.lastError());
    }

    @Override
    public void updateMessageState(String messageId, String deliveryState, String verificationState, String lastError) {
        db.update("UPDATE polymail_messages SET delivery_state=?, verification_state=?, last_error_code=? WHERE message_id=?",
                deliveryState, verificationState, lastError, messageId);
    }

    @Override
    public void updateMessageNotification(String messageId, String txHash) {
        db.update("UPDATE polymail_messages SET notification_transaction_hash=? WHERE message_id=?", txHash, messageId);
    }

    @Override
    public boolean hasMessageRecord(String messageId) { return messageExists(messageId); }

    @Override
    public com.graalmail.p2p.delivery.MessageRecord messageRecord(String messageId) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM polymail_messages WHERE message_id = ?", messageId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new com.graalmail.p2p.delivery.MessageRecord(
                str(r.get("message_id")), str(r.get("direction")), str(r.get("sender_identity")),
                str(r.get("recipient_identity")), ((Number) r.get("created_at")).longValue(),
                ((Number) r.get("expires_at")).longValue(), str(r.get("content_hash")),
                str(r.get("notification_transaction_hash")), str(r.get("delivery_state")),
                str(r.get("verification_state")), str(r.get("last_error_code")));
    }

    private static byte[] blob(Object o) {
        if (o == null) return null;
        if (o instanceof byte[] b) return b;
        return o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private static byte[] hexToBytes(String h) {
        if (h == null || h.isEmpty()) return new byte[0];
        String s = h.startsWith("0x") ? h.substring(2) : h;
        if (s.length() % 2 != 0) s = "0" + s;
        byte[] out = new byte[s.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            return new byte[0];
        }
        return out;
    }
}
