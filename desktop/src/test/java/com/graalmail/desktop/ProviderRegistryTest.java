/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.provider.api.AccountProvider;
import com.graalmail.provider.api.ProviderRegistry;
import com.graalmail.provider.api.ProviderType;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every provider of the distribution is discovered through the
 * service loader and reports its capabilities.
 */
class ProviderRegistryTest {

    @Test
    void findsEveryProviderOfTheDistribution() {
        Set<ProviderType> found = EnumSet.noneOf(ProviderType.class);
        for (AccountProvider provider : ProviderRegistry.loadAll()) {
            found.add(provider.type());
            assertNotNull(provider.capabilities());
            assertTrue(provider.capabilities().authMethods().size() > 0,
                    provider.type() + " must declare at least one authentication method");
        }
        assertTrue(found.containsAll(EnumSet.allOf(ProviderType.class)),
                "missing providers: " + EnumSet.allOf(ProviderType.class).stream()
                        .filter(type -> !found.contains(type)).toList());
    }

    @Test
    void resolvesProvidersByType() {
        for (ProviderType type : ProviderType.values()) {
            AccountProvider provider = ProviderRegistry.find(type).orElse(null);
            assertNotNull(provider, "no provider for " + type);
            assertEquals(type, provider.type());
        }
    }
}
