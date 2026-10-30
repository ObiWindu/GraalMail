/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC backed SQL database (SQLite on the desktop). */
public final class JdbcSqlDatabase implements SqlDatabase {

    private final Connection connection;

    public JdbcSqlDatabase(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            this.connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to open the local database", e);
        }
    }

    @Override
    public synchronized void execute(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }

    @Override
    public synchronized int update(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }

    @Override
    public synchronized List<Map<String, Object>> query(String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL failed: " + sql, e);
        }
    }

    @Override
    public <T> T inTransaction(SqlTransaction<T> transaction) {
        synchronized (this) {
            try {
                connection.setAutoCommit(false);
                T result = transaction.run();
                connection.commit();
                return result;
            } catch (SQLException e) {
                throw new IllegalStateException("Transaction failed", e);
            } catch (RuntimeException | Error e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // the original failure is more interesting
                }
                throw e;
            } finally {
                restoreAutoCommit();
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // closing errors are not actionable
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // nothing more we can do
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Utility for callers that need a plain statement. */
    public void executeRaw(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
