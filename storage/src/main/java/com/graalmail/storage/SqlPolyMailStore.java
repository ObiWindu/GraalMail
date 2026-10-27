package com.graalmail.storage;

import com.graalmail.p2p.blockchain.BlockchainCursor;
import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.mailbox.StorageReceipt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SqlPolyMailStore implements PolyMailStore {
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
        db.update("INSERT INTO polymail_storage_receipts (receipt_id, message_id, content_hash, peer_id, receipt_payload, accepted_at, retention_until, verification_state) VALUES (?,?,?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), receipt.messageId(), receipt.contentId(), receipt.peerId(),
                "", receipt.acceptedAt(), receipt.retentionUntil(), "UNVERIFIED");
    }

    @Override
    public List<StorageReceipt> receiptsForMessage(String messageId) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM polymail_storage_receipts WHERE message_id = ?", messageId);
        List<StorageReceipt> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(new StorageReceipt(str(r.get("content_hash")), str(r.get("message_id")), str(r.get("peer_id")),
                    ((Number) r.get("accepted_at")).longValue(), ((Number) r.get("retention_until")).longValue(),
                    "GPMP/1", new byte[0]));
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
        if (loadCursor(cursor.chainId(), cursor.contractAddress()).lastBlock() == 0) {
            db.update("INSERT INTO polymail_blockchain_cursor (chain_id, contract_address, last_block, block_hash) VALUES (?,?,?,?)",
                    cursor.chainId(), cursor.contractAddress(), cursor.lastBlock(), cursor.blockHash());
        } else {
            db.update("UPDATE polymail_blockchain_cursor SET last_block=?, block_hash=? WHERE chain_id=? AND contract_address=?",
                    cursor.lastBlock(), cursor.blockHash(), cursor.chainId(), cursor.contractAddress());
        }
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
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
