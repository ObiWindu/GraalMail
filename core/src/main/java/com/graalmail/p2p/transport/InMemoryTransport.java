package com.graalmail.p2p.transport;

import com.graalmail.p2p.PeerAddress;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Consumer;

public class InMemoryTransport implements P2PTransport {
    private final Map<PeerAddress, Consumer<byte[]>> handlers = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public void register(PeerAddress address, Consumer<byte[]> handler) { handlers.put(address, handler); }

    @Override
    public CompletionStage<PeerConnection> connect(PeerAddress address) {
        return CompletableFuture.completedFuture(new PeerConnection() {
            @Override
            public CompletionStage<Void> send(byte[] data) {
                Consumer<byte[]> h = handlers.get(address);
                if (h != null) h.accept(data);
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletionStage<byte[]> receive() { return CompletableFuture.completedFuture(new byte[0]); }
            @Override
            public void close() { }
        });
    }

    @Override
    public CompletionStage<Void> start() { running = true; return CompletableFuture.completedFuture(null); }
    @Override
    public CompletionStage<Void> stop() { running = false; return CompletableFuture.completedFuture(null); }
    @Override
    public NetworkStatus getStatus() { return new NetworkStatus(running, handlers.size(), "in-memory"); }
    @Override
    public void close() { running = false; }
}
