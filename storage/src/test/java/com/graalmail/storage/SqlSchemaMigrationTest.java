/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Existing installs upgrading to schema v6 must keep their PolyMail rows and
 * gain the durable-delivery columns (envelope payload, receipt signatures).
 */
class SqlSchemaMigrationTest {

    private JdbcSqlDatabase database;

    @AfterEach
    void tearDown() {
        if (database != null) {
            try {
                database.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Test
    void migratesV5ToV6PreservingRows() {
        database = new JdbcSqlDatabase("jdbc:sqlite::memory:");
        // Minimal v5 shape: PolyMail tables without the v6 columns.
        database.execute("CREATE TABLE schema_version (version INTEGER NOT NULL)");
        database.execute("CREATE TABLE polymail_outgoing_queue ("
                + "operation_id TEXT PRIMARY KEY, message_id TEXT NOT NULL, operation_type TEXT NOT NULL, "
                + "current_state TEXT NOT NULL, retry_count INTEGER NOT NULL DEFAULT 0, "
                + "next_attempt_at INTEGER NOT NULL, last_error_code TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        database.execute("CREATE TABLE polymail_storage_receipts ("
                + "receipt_id TEXT PRIMARY KEY, message_id TEXT NOT NULL, content_hash TEXT NOT NULL, "
                + "peer_id TEXT NOT NULL, receipt_payload TEXT NOT NULL DEFAULT '', "
                + "accepted_at INTEGER NOT NULL, retention_until INTEGER NOT NULL, "
                + "verification_state TEXT NOT NULL)");
        database.update("INSERT INTO polymail_outgoing_queue VALUES (?,?,?,?,?,?,?,?,?)",
                "op-1", "msg-1", "STORE", "QUEUED", 0, 1000L, "", 1000L, 1000L);
        database.update("INSERT INTO polymail_storage_receipts VALUES (?,?,?,?,?,?,?,?)",
                "rc-1", "msg-1", "hash-1", "peer-1", "payload", 1000L, 2000L, "UNVERIFIED");
        database.update("INSERT INTO schema_version (version) VALUES (?)", 5);

        SqlSchema.migrate(database);

        List<Map<String, Object>> version =
                database.query("SELECT version FROM schema_version");
        assertEquals(1, version.size());
        assertEquals(6, ((Number) version.get(0).get("version")).intValue());

        List<Map<String, Object>> outgoing =
                database.query("SELECT * FROM polymail_outgoing_queue WHERE operation_id='op-1'");
        assertEquals(1, outgoing.size());
        assertEquals("msg-1", outgoing.get(0).get("message_id"));
        assertTrue(outgoing.get(0).containsKey("payload"));

        List<Map<String, Object>> receipts =
                database.query("SELECT * FROM polymail_storage_receipts WHERE receipt_id='rc-1'");
        assertEquals(1, receipts.size());
        assertEquals("UNVERIFIED", receipts.get(0).get("verification_state"));
        assertTrue(receipts.get(0).containsKey("receipt_signature"));
    }

    @Test
    void freshInstallLandsOnV6() {
        database = new JdbcSqlDatabase("jdbc:sqlite::memory:");
        SqlSchema.migrate(database);
        List<Map<String, Object>> version =
                database.query("SELECT version FROM schema_version");
        assertEquals(SqlSchema.VERSION, ((Number) version.get(0).get("version")).intValue());
    }
}
