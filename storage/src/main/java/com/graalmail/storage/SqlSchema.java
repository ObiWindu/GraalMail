/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import java.util.List;
import java.util.Map;

/** Creates and migrates the local database schema. */
public final class SqlSchema {

    public static final int VERSION = 1;

    private SqlSchema() {
    }

    public static void migrate(SqlDatabase db) {
        db.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        List<Map<String, Object>> rows = db.query("SELECT version FROM schema_version");
        int current = rows.isEmpty() ? 0 : ((Number) rows.get(0).get("version")).intValue();
        if (current < 1) {
            createVersion1(db);
            db.update("DELETE FROM schema_version");
            db.update("INSERT INTO schema_version (version) VALUES (?)", VERSION);
        }
    }

    private static void createVersion1(SqlDatabase db) {
        db.execute("CREATE TABLE IF NOT EXISTS accounts ("
                + "id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, email TEXT NOT NULL, "
                + "display_name TEXT NOT NULL DEFAULT '', settings TEXT NOT NULL DEFAULT '', "
                + "credential_ref TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1, "
                + "created_at INTEGER NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS folders ("
                + "id TEXT NOT NULL, account_id TEXT NOT NULL, name TEXT NOT NULL, "
                + "parent_path TEXT NOT NULL DEFAULT '', type TEXT NOT NULL, "
                + "total_count INTEGER NOT NULL DEFAULT 0, unread_count INTEGER NOT NULL DEFAULT 0, "
                + "selectable INTEGER NOT NULL DEFAULT 1, supports_labels INTEGER NOT NULL DEFAULT 0, "
                + "provider_type TEXT NOT NULL DEFAULT '', PRIMARY KEY (account_id, id))");
        db.execute("CREATE TABLE IF NOT EXISTS messages ("
                + "id TEXT PRIMARY KEY, account_id TEXT NOT NULL, folder_id TEXT NOT NULL, "
                + "internet_message_id TEXT NOT NULL DEFAULT '', subject TEXT NOT NULL DEFAULT '', "
                + "from_name TEXT NOT NULL DEFAULT '', from_email TEXT NOT NULL DEFAULT '', "
                + "recipients TEXT NOT NULL DEFAULT '', snippet TEXT NOT NULL DEFAULT '', "
                + "sent_at INTEGER, unread INTEGER NOT NULL DEFAULT 1, flagged INTEGER NOT NULL DEFAULT 0, "
                + "draft INTEGER NOT NULL DEFAULT 0, answered INTEGER NOT NULL DEFAULT 0, "
                + "has_attachments INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL DEFAULT 0, "
                + "body_text TEXT, body_html TEXT, headers TEXT NOT NULL DEFAULT '', "
                + "attachments TEXT NOT NULL DEFAULT '', cached_at INTEGER NOT NULL)");
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_folder ON messages (account_id, folder_id)");
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_date ON messages (sent_at)");
        db.execute("CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages (account_id, unread)");
        db.execute("CREATE TABLE IF NOT EXISTS contacts ("
                + "id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', email TEXT NOT NULL, "
                + "note TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS pending_actions ("
                + "id TEXT PRIMARY KEY, account_id TEXT NOT NULL, type TEXT NOT NULL, "
                + "target_id TEXT NOT NULL DEFAULT '', target_folder_id TEXT NOT NULL DEFAULT '', "
                + "payload TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, "
                + "last_error TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)");
        db.execute("CREATE INDEX IF NOT EXISTS idx_pending_account ON pending_actions (account_id, state)");
        db.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    }
}
