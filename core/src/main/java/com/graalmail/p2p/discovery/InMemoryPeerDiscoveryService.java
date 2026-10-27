package com.graalmail.p2p.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemoryPeerDiscoveryService implements PeerDiscoveryService {
    private final Map<String, List<PeerRecord>> byNetwork = new ConcurrentHashMap<>();

    @Override
    public java.util.concurrent.CompletionStage<Void> announce(PeerRecord record) {
        byNetwork.computeIfAbsent(record.networkId(), k -> new ArrayList<>()).add(record);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public java.util.concurrent.CompletionStage<List<PeerRecord>> findPeers(String networkId) {
        return CompletableFuture.completedFuture(new ArrayList<>(byNetwork.getOrDefault(networkId, List.of())));
    }

    @Override
    public java.util.concurrent.CompletionStage<Void> expireStale(long now) {
        for (List<PeerRecord> list : byNetwork.values()) {
            list.removeIf(r -> r.expiresAt() > 0 && now > r.expiresAt());
        }
        return CompletableFuture.completedFuture(null);
    }
}
