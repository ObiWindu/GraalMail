/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import java.util.List;
import java.util.Map;

/**
 * Minimal SQL abstraction so the same storage code runs on desktop JDBC and
 * on the Android SQLite API.
 */
public interface SqlDatabase extends AutoCloseable {

    void execute(String sql, Object... params);

    int update(String sql, Object... params);

    List<Map<String, Object>> query(String sql, Object... params);

    <T> T inTransaction(SqlTransaction<T> transaction);

    @Override
    void close();

    /** Work executed inside a transaction. */
    interface SqlTransaction<T> {
        T run();
    }
}
