package com.graalmail.p2p;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.mailbox.StorageReceipt;
import com.graalmail.p2p.protocol.Envelope;
import com.graalmail.p2p.transport.NetworkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemoryPeerNetworkService implements PeerNetworkService {
    private final Map<PeerAddress, MailboxPeer> peers = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    @Override
    public java.util.concurrent.CompletionStage<Void> start() { running = true; return CompletableFuture.completedFuture(null); }
    @Override
    public java.util.concurrent.CompletionStage<Void> stop() { running = false; return CompletableFuture.completedFuture(null); }
    @Override
    public NetworkStatus getStatus() { return new NetworkStatus(running, peers.size(), "in-memory"); }

    @Override
    public void registerPeer(PeerAddress address, MailboxPeer peer) { peers.put(address, peer); }

    @Override
    public java.util.concurrent.CompletionStage<StorageReceipt> storeMessage(PeerAddress peer, Envelope envelope) {
        MailboxPeer mp = peers.get(peer);
        if (mp == null) return CompletableFuture.failedFuture(new PolyMailException(com.graalmail.core.error.ErrorCode.PEER_UNREACHABLE, "peer not found"));
        return mp.store(envelope);
    }

    @Override
    public java.util.concurrent.CompletionStage<Envelope> fetchMessage(PeerAddress peer, String contentId) {
        MailboxPeer mp = peers.get(peer);
        if (mp == null) return CompletableFuture.failedFuture(new PolyMailException(com.graalmail.core.error.ErrorCode.PEER_UNREACHABLE, "peer not found"));
        return mp.fetch(contentId);
    }

    @Override
    public java.util.concurrent.CompletionStage<List<PeerAddress>> discoverPeers() {
        return CompletableFuture.completedFuture(new ArrayList<>(peers.keySet()));
    }
}
