/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.LocalDraft;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Attachment;
import com.graalmail.core.domain.Folder;
import com.graalmail.core.domain.FolderType;
import com.graalmail.core.domain.Message;
import com.graalmail.core.domain.MessageListPage;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.domain.SyncState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SQL implementation of {@link MailStore}. */
public final class SqlMailStore implements MailStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SqlDatabase db;

    public SqlMailStore(SqlDatabase db) {
        this.db = db;
    }

    @Override
    public void initialize() {
        SqlSchema.migrate(db);
    }

    @Override
    public List<Account> listAccounts() {
        List<Map<String, Object>> rows = db.query("SELECT * FROM accounts ORDER BY created_at");
        List<Account> accounts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            accounts.add(toAccount(row));
        }
        return accounts;
    }

    @Override
    public Optional<Account> account(String accountId) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM accounts WHERE id = ?", accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toAccount(rows.get(0)));
    }

    @Override
    public void saveAccount(Account account) {
        db.update("INSERT INTO accounts (id, provider_id, email, display_name, settings, "
                        + "credential_ref, enabled, created_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET provider_id=excluded.provider_id, "
                        + "email=excluded.email, display_name=excluded.display_name, "
                        + "settings=excluded.settings, credential_ref=excluded.credential_ref, "
                        + "enabled=excluded.enabled",
                account.id(), account.providerId(), account.email(), account.displayName(),
                json(account.settings()), account.credentialRef(), account.enabled() ? 1 : 0,
                account.createdAt().toEpochMilli());
    }

    @Override
    public void deleteAccount(String accountId) {
        db.update("DELETE FROM accounts WHERE id = ?", accountId);
        db.update("DELETE FROM folders WHERE account_id = ?", accountId);
        db.update("DELETE FROM messages WHERE account_id = ?", accountId);
        db.update("DELETE FROM pending_actions WHERE account_id = ?", accountId);
    }

    @Override
    public List<Folder> listFolders(String accountId) {
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM folders WHERE account_id = ? ORDER BY type, name", accountId);
        List<Folder> folders = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            folders.add(toFolder(row));
        }
        return folders;
    }

    @Override
    public Optional<Folder> folder(String accountId, String folderId) {
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM folders WHERE account_id = ? AND id = ?", accountId, folderId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toFolder(rows.get(0)));
    }

    @Override
    public void saveFolders(String accountId, List<Folder> folders) {
        db.inTransaction(() -> {
            for (Folder folder : folders) {
                String name = encryptIfNeeded("folders", "name", folder.name());
                String providerType = encryptIfNeeded("folders", "provider_type", folder.providerSpecificType());
                db.update("INSERT INTO folders (id, account_id, name, parent_path, type, total_count, "
                                + "unread_count, selectable, supports_labels, provider_type, "
                                + "uid_validity, uid_next, last_sync_at, highest_mod_seq, sync_state) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                                + "ON CONFLICT(account_id, id) DO UPDATE SET name=excluded.name, "
                                + "parent_path=excluded.parent_path, type=excluded.type, "
                                + "total_count=excluded.total_count, unread_count=excluded.unread_count, "
                                + "selectable=excluded.selectable, "
                                + "supports_labels=excluded.supports_labels, "
                                + "provider_type=excluded.provider_type, "
                                + "uid_validity=excluded.uid_validity, uid_next=excluded.uid_next, "
                                + "last_sync_at=excluded.last_sync_at, highest_mod_seq=excluded.highest_mod_seq, "
                                + "sync_state=excluded.sync_state",
                        folder.id(), accountId, name, folder.parentPath(), folder.type().name(),
                        folder.totalCount(), folder.unreadCount(), folder.selectable() ? 1 : 0,
                        folder.supportsLabels() ? 1 : 0, providerType,
                        folder.uidValidity(), folder.uidNext(), folder.lastSyncAt(),
                        folder.highestModSeq(), folder.syncState().name());
            }
            return null;
        });
    }

    @Override
    public MessageListPage listMessages(String accountId, String folderId, int limit, int offset) {
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM messages WHERE account_id = ? AND folder_id = ? "
                        + "ORDER BY sent_at DESC LIMIT ? OFFSET ?",
                accountId, folderId, limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            messages.add(toMessage(row));
        }
        List<Map<String, Object>> counts = db.query(
                "SELECT COUNT(*) AS total FROM messages WHERE account_id = ? AND folder_id = ?",
                accountId, folderId);
        long total = counts.isEmpty() ? messages.size() : ((Number) counts.get(0).get("total")).longValue();
        return new MessageListPage(messages, hasMore, total);
    }

    @Override
    public Optional<Message> message(String messageId) {
        List<Map<String, Object>> rows = db.query("SELECT * FROM messages WHERE id = ?", messageId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toMessage(rows.get(0)));
    }

    @Override
    public void saveMessage(Message message) {
        // Encrypt sensitive message fields if using EncryptedSqlDatabase
        String bodyText = encryptIfNeeded("messages", "body_text", message.bodyText());
        String bodyHtml = encryptIfNeeded("messages", "body_html", message.bodyHtml());
        String headers = encryptIfNeeded("messages", "headers", json(message.headers()));
        String attachments = encryptIfNeeded("messages", "attachments", json(message.attachments()));

        db.update("INSERT INTO messages (id, account_id, folder_id, internet_message_id, subject, "
                        + "from_name, from_email, recipients, snippet, sent_at, unread, flagged, draft, "
                        + "answered, has_attachments, size, body_text, body_html, headers, attachments, "
                        + "cached_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET folder_id=excluded.folder_id, "
                        + "subject=excluded.subject, snippet=excluded.snippet, unread=excluded.unread, "
                        + "flagged=excluded.flagged, draft=excluded.draft, answered=excluded.answered, "
                        + "has_attachments=excluded.has_attachments, size=excluded.size, "
                        + "body_text=excluded.body_text, body_html=excluded.body_html, "
                        + "headers=excluded.headers, attachments=excluded.attachments",
                message.id(), message.accountId(), message.folderId(), message.internetMessageId(),
                message.subject(), message.from().name(), message.from().email(),
                json(message.to().stream().map(Address::format).toList()),
                message.snippet(), epoch(message.date()), flag(message.unread()),
                flag(message.flagged()), flag(message.draft()), flag(message.answered()),
                flag(message.hasAttachments()), message.size(), bodyText, bodyHtml,
                headers, attachments, Instant.now().toEpochMilli());
    }

    @Override
    public void updateFlags(String messageId, boolean unread, boolean flagged) {
        db.update("UPDATE messages SET unread = ?, flagged = ? WHERE id = ?",
                flag(unread), flag(flagged), messageId);
    }

    @Override
    public void moveMessage(String messageId, String folderId) {
        db.update("UPDATE messages SET folder_id = ? WHERE id = ?", folderId, messageId);
    }

    @Override
    public void deleteMessage(String messageId) {
        db.update("DELETE FROM messages WHERE id = ?", messageId);
    }

    @Override
    public List<Message> search(SearchQuery query, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM messages WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!query.text().isBlank()) {
            sql.append(" AND (subject LIKE ? OR body_text LIKE ? OR from_email LIKE ? OR from_name LIKE ?)");
            String like = "%" + query.text() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (!query.from().isBlank()) {
            sql.append(" AND (from_email LIKE ? OR from_name LIKE ?)");
            params.add("%" + query.from() + "%");
            params.add("%" + query.from() + "%");
        }
        if (!query.to().isBlank()) {
            sql.append(" AND recipients LIKE ?");
            params.add("%" + query.to() + "%");
        }
        if (!query.subject().isBlank()) {
            sql.append(" AND subject LIKE ?");
            params.add("%" + query.subject() + "%");
        }
        if (query.unreadOnly()) {
            sql.append(" AND unread = 1");
        }
        if (query.flaggedOnly()) {
            sql.append(" AND flagged = 1");
        }
        if (query.withAttachmentsOnly()) {
            sql.append(" AND has_attachments = 1");
        }
        if (query.since() != null) {
            sql.append(" AND sent_at >= ?");
            params.add(query.since().toEpochMilli());
        }
        if (query.until() != null) {
            sql.append(" AND sent_at <= ?");
            params.add(query.until().toEpochMilli());
        }
        if (!query.folderIds().isEmpty()) {
            sql.append(" AND folder_id IN (");
            for (int i = 0; i < query.folderIds().size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                params.add(query.folderIds().get(i));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY sent_at DESC LIMIT ?");
        params.add(limit);
        List<Map<String, Object>> rows = db.query(sql.toString(), params.toArray());
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            messages.add(toMessage(row));
        }
        return messages;
    }

    @Override
    public void enqueue(PendingAction action) {
        db.update("INSERT INTO pending_actions (id, account_id, type, target_id, target_folder_id, "
                        + "payload, state, attempts, last_error, created_at, send_state) VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET state=excluded.state, "
                        + "attempts=excluded.attempts, last_error=excluded.last_error, send_state=excluded.send_state",
                action.id(), action.accountId(), action.type().name(), action.targetId(),
                action.targetFolderId(), action.payload(), action.state().name(), action.attempts(),
                action.lastError(), action.createdAt().toEpochMilli(),
                action.sendState() == null ? "" : action.sendState().name());
    }

    @Override
    public List<PendingAction> pendingActions(String accountId) {
        String sql = "SELECT * FROM pending_actions WHERE account_id = ? AND state IN ('PENDING','FAILED') "
                + "ORDER BY created_at";
        List<Map<String, Object>> rows = db.query(sql, accountId);
        List<PendingAction> actions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            actions.add(toPendingAction(row));
        }
        return actions;
    }

    @Override
    public void updatePendingAction(PendingAction action) {
        db.update("UPDATE pending_actions SET state = ?, attempts = ?, last_error = ?, send_state = ? WHERE id = ?",
                action.state().name(), action.attempts(), action.lastError(),
                action.sendState() == null ? "" : action.sendState().name(), action.id());
    }

    @Override
    public List<Contact> listContacts() {
        List<Map<String, Object>> rows = db.query("SELECT * FROM contacts ORDER BY name, email");
        List<Contact> contacts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = decryptStr("contacts", "name", row, "name");
            String email = decryptStr("contacts", "email", row, "email");
            String note = decryptStr("contacts", "note", row, "note");
            contacts.add(new Contact(str(row, "id"), name, email, note,
                    Instant.ofEpochMilli(number(row, "updated_at"))));
        }
        return contacts;
    }

    @Override
    public void saveContact(Contact contact) {
        String name = encryptIfNeeded("contacts", "name", contact.name());
        String email = encryptIfNeeded("contacts", "email", contact.email());
        String note = encryptIfNeeded("contacts", "note", contact.note());
        db.update("INSERT INTO contacts (id, name, email, note, updated_at) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET name=excluded.name, email=excluded.email, "
                        + "note=excluded.note, updated_at=excluded.updated_at",
                contact.id(), name, email, note,
                contact.updatedAt().toEpochMilli());
    }

    @Override
    public void deleteContact(String contactId) {
        db.update("DELETE FROM contacts WHERE id = ?", contactId);
    }

    @Override
    public String setting(String key, String fallback) {
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key = ?", key);
        return rows.isEmpty() ? fallback : str(rows.get(0), "value");
    }

    @Override
    public void putSetting(String key, String value) {
        db.update("INSERT INTO settings (key, value) VALUES (?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    @Override
    public void clearMessageCache(String accountId) {
        db.update("DELETE FROM messages WHERE account_id = ?", accountId);
    }

    @Override
    public void clearMessageCache(String accountId, String folderId) {
        db.update("DELETE FROM messages WHERE account_id = ? AND folder_id = ?", accountId, folderId);
    }

    @Override
    public void saveLocalDraft(LocalDraft draft) {
        String draftJson = encryptIfNeeded("local_drafts", "draft_json", draft.draftJson());
        db.update("INSERT INTO local_drafts (id, account_id, folder_id, draft_json, updated_at, is_recovered) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                + "account_id=excluded.account_id, folder_id=excluded.folder_id, "
                + "draft_json=excluded.draft_json, updated_at=excluded.updated_at, "
                + "is_recovered=excluded.is_recovered",
                draft.id(), draft.accountId(), draft.folderId(), draftJson,
                draft.updatedAt(), draft.recovered() ? 1 : 0);
    }

    @Override
    public Optional<LocalDraft> getLocalDraft(String accountId) {
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM local_drafts WHERE account_id = ? ORDER BY updated_at DESC LIMIT 1", accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toLocalDraft(rows.get(0)));
    }

    @Override
    public void deleteLocalDraft(String draftId) {
        db.update("DELETE FROM local_drafts WHERE id = ?", draftId);
    }

    @Override
    public List<LocalDraft> listRecoveredDrafts() {
        List<Map<String, Object>> rows = db.query(
                "SELECT * FROM local_drafts WHERE is_recovered = 1 ORDER BY updated_at DESC");
        List<LocalDraft> drafts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            drafts.add(toLocalDraft(row));
        }
        return drafts;
    }

    @Override
    public void close() {
        db.close();
    }

    private String encryptIfNeeded(String table, String column, String value) {
        if (db instanceof EncryptedSqlDatabase encryptedDb) {
            Object result = encryptedDb.encryptForColumn(table, column, value);
            return result instanceof String ? (String) result : value;
        }
        return value;
    }

    private String decryptStr(String table, String column, Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (db instanceof EncryptedSqlDatabase encryptedDb) {
            Object decrypted = encryptedDb.decryptForColumn(table, column, value);
            return decrypted == null ? "" : String.valueOf(decrypted);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDraft toLocalDraft(Map<String, Object> row) {
        String draftJson = decryptStr("local_drafts", "draft_json", row, "draft_json");
        return new LocalDraft(str(row, "id"), str(row, "account_id"), str(row, "folder_id"),
                draftJson, number(row, "updated_at"), number(row, "is_recovered") != 0);
    }

    private Account toAccount(Map<String, Object> row) {
        Map<String, String> settings = new LinkedHashMap<>();
        String raw = str(row, "settings");
        if (!raw.isBlank()) {
            try {
                settings.putAll(MAPPER.readValue(raw, new TypeReference<Map<String, String>>() {
                }));
            } catch (Exception ignored) {
                // malformed settings must not break the account list
            }
        }
        return Account.builder(str(row, "id"), str(row, "provider_id"))
                .email(str(row, "email"))
                .displayName(str(row, "display_name"))
                .settings(settings)
                .credentialRef(str(row, "credential_ref"))
                .enabled(number(row, "enabled") != 0)
                .createdAt(Instant.ofEpochMilli(number(row, "created_at")))
                .build();
    }

    private Folder toFolder(Map<String, Object> row) {
        FolderType type;
        try {
            type = FolderType.valueOf(str(row, "type"));
        } catch (IllegalArgumentException e) {
            type = FolderType.CUSTOM;
        }
        SyncState syncState;
        try {
            syncState = SyncState.valueOf(str(row, "sync_state"));
        } catch (IllegalArgumentException e) {
            syncState = SyncState.NEVER;
        }
        String name = decryptStr("folders", "name", row, "name");
        String providerType = decryptStr("folders", "provider_type", row, "provider_type");
        return new Folder(str(row, "id"), name, str(row, "parent_path"), type,
                (int) number(row, "total_count"), (int) number(row, "unread_count"),
                number(row, "selectable") != 0, number(row, "supports_labels") != 0,
                providerType,
                number(row, "uid_validity"), number(row, "uid_next"),
                number(row, "last_sync_at"), str(row, "highest_mod_seq"), syncState);
    }

    private Message toMessage(Map<String, Object> row) {
        String bodyText = decryptStr("messages", "body_text", row, "body_text");
        String bodyHtml = decryptStr("messages", "body_html", row, "body_html");
        String headers = decryptStr("messages", "headers", row, "headers");
        String attachments = decryptStr("messages", "attachments", row, "attachments");
        
        Message.Builder builder = Message.builder(str(row, "id"))
                .accountId(str(row, "account_id"))
                .folderId(str(row, "folder_id"))
                .internetMessageId(str(row, "internet_message_id"))
                .subject(str(row, "subject"))
                .from(new Address(str(row, "from_name"), str(row, "from_email")))
                .to(readAddresses(str(row, "recipients")))
                .snippet(str(row, "snippet"))
                .unread(number(row, "unread") != 0)
                .flagged(number(row, "flagged") != 0)
                .draft(number(row, "draft") != 0)
                .answered(number(row, "answered") != 0)
                .attachment(number(row, "has_attachments") != 0)
                .size(number(row, "size"))
                .bodyText(bodyText)
                .bodyHtml(bodyHtml)
                .headers(readHeaders(headers))
                .attachments(readAttachments(attachments));
        Long sentAt = row.get("sent_at") == null ? null : number(row, "sent_at");
        if (sentAt != null && sentAt > 0) {
            builder.date(Instant.ofEpochMilli(sentAt));
        }
        return builder.build();
    }

    private PendingAction toPendingAction(Map<String, Object> row) {
        String sendStateStr = str(row, "send_state");
        PendingAction.SendState sendState = null;
        if (sendStateStr != null && !sendStateStr.isBlank()) {
            try { sendState = PendingAction.SendState.valueOf(sendStateStr); } catch (IllegalArgumentException ignored) {}
        }
        return new PendingAction(str(row, "id"), str(row, "account_id"),
                PendingAction.Type.valueOf(str(row, "type")), str(row, "target_id"),
                str(row, "target_folder_id"), str(row, "payload"),
                PendingAction.State.valueOf(str(row, "state")), (int) number(row, "attempts"),
                str(row, "last_error"), Instant.ofEpochMilli(number(row, "created_at")),
                sendState);
    }

    private static List<Address> readAddresses(String json) {
        List<Address> addresses = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return addresses;
        }
        try {
            for (String value : MAPPER.readValue(json, new TypeReference<List<String>>() {
            })) {
                addresses.add(Address.parse(value));
            }
        } catch (Exception ignored) {
            // ignore malformed recipient data
        }
        return addresses;
    }

    private static List<Message.Header> readHeaders(String json) {
        List<Message.Header> headers = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return headers;
        }
        try {
            for (String value : MAPPER.readValue(json, new TypeReference<List<String>>() {
            })) {
                int idx = value.indexOf(':');
                if (idx > 0) {
                    headers.add(new Message.Header(value.substring(0, idx), value.substring(idx + 1).trim()));
                }
            }
        } catch (Exception ignored) {
            // ignore malformed header data
        }
        return headers;
    }

    private static List<Attachment> readAttachments(String json) {
        List<Attachment> attachments = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return attachments;
        }
        try {
            for (String value : MAPPER.readValue(json, new TypeReference<List<String>>() {
            })) {
                String[] parts = value.split("\\|", -1);
                if (parts.length >= 4) {
                    attachments.add(new Attachment(parts[0], parts[1], parts[2], Long.parseLong(parts[3]),
                            Boolean.parseBoolean(parts[4] == null || parts.length < 5 ? "false" : parts[4]),
                            parts.length > 5 ? parts[5] : ""));
                }
            }
        } catch (Exception ignored) {
            // ignore malformed attachment metadata
        }
        return attachments;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static Long epoch(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
