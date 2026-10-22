/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.security.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local credential store; used by tests and as a last-resort fallback. */
public final class InMemoryCredentialStore implements CredentialStore {

    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();

    @Override
    public String read(String accountId, String key) {
        Map<String, String> account = data.get(accountId);
        return account == null ? null : account.get(key);
    }

    @Override
    public void write(String accountId, String key, String value) {
        data.computeIfAbsent(accountId, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void delete(String accountId) {
        data.remove(accountId);
    }

    @Override
    public void deleteAll() {
        data.clear();
    }
}
