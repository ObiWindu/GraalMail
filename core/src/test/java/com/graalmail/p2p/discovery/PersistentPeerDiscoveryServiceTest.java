package com.graalmail.p2p.discovery;

import static org.junit.jupiter.api.Assertions.*;

import com.graalmail.p2p.PeerId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentPeerDiscoveryServiceTest {

    @TempDir
    Path temp;

    private static PeerRecord record(String id, String network, long expiresAt) {
        return new PeerRecord(new PeerId(id), network, "GPMP/1",
                List.of("tcp"), List.of("127.0.0.1:9000"),
                true, System.currentTimeMillis(), expiresAt, new byte[64]);
    }

    @Test
    void announcesFindsAndExpires() throws Exception {
        Path file = temp.resolve("peers.tsv");
        PersistentPeerDiscoveryService discovery = new PersistentPeerDiscoveryService(file);
        discovery.announce(record("peer-a", "test", 0)).toCompletableFuture().get();
        discovery.announce(record("peer-stale", "test", System.currentTimeMillis() - 1000))
                .toCompletableFuture().get();
        List<PeerRecord> found = discovery.findPeers("test").toCompletableFuture().get();
        assertTrue(found.stream().anyMatch(r -> r.peerId().value().equals("peer-a")));
        assertFalse(found.stream().anyMatch(r -> r.peerId().value().equals("peer-stale")));
        discovery.expireStale(System.currentTimeMillis()).toCompletableFuture().get();
        // Reload from disk: the valid record survives restarts.
        PersistentPeerDiscoveryService reloaded = new PersistentPeerDiscoveryService(file);
        List<PeerRecord> again = reloaded.findPeers("test").toCompletableFuture().get();
        assertTrue(again.stream().anyMatch(r -> r.peerId().value().equals("peer-a")));
    }

    @Test
    void rejectsInvalidRecords() {
        PersistentPeerDiscoveryService discovery =
                new PersistentPeerDiscoveryService(temp.resolve("other.tsv"));
        PeerRecord invalid = new PeerRecord(new PeerId("peer-b"), "", "GPMP/1",
                List.of(), List.of(), false, 0, 0, new byte[0]);
        assertTrue(discovery.announce(invalid).toCompletableFuture().isCompletedExceptionally());
    }
}
