/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.store;

/** Platform-neutral secret storage SPI. */
public interface CredentialStore extends AutoCloseable {

    /** Returns the stored secret or null when absent. */
    String read(String accountId, String key);

    void write(String accountId, String key, String value);

    void delete(String accountId);

    void deleteAll();

    @Override
    default void close() {
        // nothing by default
    }
}
