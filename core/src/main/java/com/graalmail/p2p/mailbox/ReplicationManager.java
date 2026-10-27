package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerNetworkService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Replication repair (spec section 9.1): messages must survive the loss of a single
 * storage peer under the configured policy. A repair pass probes every known peer
 * for the content, then tops up healthy verified replicas until the quorum holds
 * again. Repair never fabricates content: only the exact verified envelope is
 * re-replicated.
 */
public class ReplicationManager {
    private final PeerNetworkService peerNetwork;
    private final EncryptionService crypto;

    public ReplicationManager(PeerNetworkService peerNetwork, EncryptionService crypto) {
        this.peerNetwork = peerNetwork;
        this.crypto = crypto;
    }

    public CompletionStage<RepairReport> ensureReplicas(Envelope envelope, StoragePolicy policy) {
        String contentId = ContentIds.hex(envelope.contentHash());
        return peerNetwork.discoverPeers().thenCompose(peers -> {
            List<CompletableFuture<Probe>> probes = new ArrayList<>();
            for (PeerAddress peer : peers) {
                probes.add(peerNetwork.fetchMessage(peer, contentId)
                        .thenApply(fetched -> new Probe(peer, fetched != null))
                        .exceptionally(err -> new Probe(peer, false))
                        .toCompletableFuture());
            }
            return CompletableFuture.allOf(probes.toArray(new CompletableFuture<?>[0]))
                    .thenCompose(v -> {
                        Set<PeerAddress> healthy = new HashSet<>();
                        for (CompletableFuture<Probe> p : probes) {
                            try {
                                Probe probe = p.getNow(null);
                                if (probe != null && probe.healthy()) healthy.add(probe.peer());
                            } catch (Exception ignored) {}
                        }
                        int before = healthy.size();
                        if (before >= policy.requiredReplicas()) {
                            return CompletableFuture.completedFuture(
                                    new RepairReport(contentId, before, before, policy.requiredReplicas(),
                                            List.of(), List.of()));
                        }
                        List<PeerAddress> missing = new ArrayList<>();
                        for (PeerAddress peer : peers) {
                            if (!healthy.contains(peer)) missing.add(peer);
                        }
                        return repairSequentially(envelope, contentId, missing, 0, healthy,
                                new ArrayList<>(), new ArrayList<>(), policy.requiredReplicas());
                    });
        });
    }

    private CompletionStage<RepairReport> repairSequentially(Envelope envelope, String contentId,
                                                            List<PeerAddress> missing, int index,
                                                            Set<PeerAddress> healthy, List<String> repaired,
                                                            List<String> failed, int required) {
        if (index >= missing.size() || healthy.size() >= required) {
            return CompletableFuture.completedFuture(new RepairReport(contentId,
                    healthy.size() - repaired.size(), healthy.size(), required,
                    List.copyOf(repaired), List.copyOf(failed)));
        }
        PeerAddress peer = missing.get(index);
        return peerNetwork.storeMessage(peer, envelope)
                .thenCompose(receipt -> peerNetwork.peerSigningKey(peer).thenApply(key ->
                        key.isPresent() && crypto.verify(key.get(), receipt.signingBytes(), receipt.signature())))
                .thenCompose(ok -> {
                    if (ok) {
                        healthy.add(peer);
                        repaired.add(peer.toString());
                    } else {
                        failed.add(peer.toString());
                    }
                    return repairSequentially(envelope, contentId, missing, index + 1, healthy, repaired, failed, required);
                })
                .exceptionallyCompose(err -> {
                    failed.add(peer.toString());
                    return repairSequentially(envelope, contentId, missing, index + 1, healthy, repaired, failed, required);
                });
    }

    private record Probe(PeerAddress peer, boolean healthy) {}
}
