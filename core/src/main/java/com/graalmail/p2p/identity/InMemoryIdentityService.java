package com.graalmail.p2p.identity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemoryIdentityService implements IdentityService {
    private final Map<String, IdentityRecord> cache = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletionStage<IdentityRecord> resolve(String address) {
        IdentityRecord r = cache.get(address);
        if (r == null) {
            return CompletableFuture.failedFuture(new com.graalmail.p2p.PolyMailException(
                    com.graalmail.core.error.ErrorCode.IDENTITY_NOT_FOUND, "identity not found: " + address));
        }
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public boolean verify(IdentityRecord record) {
        if (record == null) return false;
        if (record.revoked()) return false;
        if (record.expiresAt() > 0 && System.currentTimeMillis() > record.expiresAt()) return false;
        return record.keyVersion() > 0 && record.signingPublicKey() != null && record.encryptionPublicKey() != null;
    }

    @Override
    public void cache(IdentityRecord record) {
        cache.put(record.username() + "@" + "graalemail", record);
    }

    @Override
    public IdentityRecord cached(String address) { return cache.get(address); }
}