/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers provider implementations through the JDK service loader so that
 * provider modules stay independent of the platform modules.
 */
public final class ProviderRegistry {

    private static final Map<ProviderType, AccountProvider> PROVIDERS = new LinkedHashMap<>();

    private ProviderRegistry() {
    }

    /** Loads every provider on the classpath exactly once. */
    public static synchronized List<AccountProvider> loadAll() {
        if (PROVIDERS.isEmpty()) {
            for (AccountProvider provider : ServiceLoader.load(AccountProvider.class)) {
                PROVIDERS.put(provider.type(), provider);
            }
        }
        return new ArrayList<>(PROVIDERS.values());
    }

    public static synchronized void register(AccountProvider provider) {
        PROVIDERS.put(provider.type(), provider);
    }

    public static synchronized Optional<AccountProvider> find(ProviderType type) {
        loadAll();
        return Optional.ofNullable(PROVIDERS.get(type));
    }

    public static synchronized Optional<AccountProvider> find(String providerId) {
        return find(ProviderType.fromId(providerId));
    }
}
