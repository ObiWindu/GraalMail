package com.graalmail.p2p.discovery;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PeerDiscoveryService {
    CompletionStage<Void> announce(PeerRecord record);
    CompletionStage<List<PeerRecord>> findPeers(String networkId);
    CompletionStage<Void> expireStale(long now);
}