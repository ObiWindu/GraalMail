package com.graalmail.p2p.transport;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.GpmpOperation;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerNetworkService;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.mailbox.StorageReceipt;
import com.graalmail.p2p.protocol.Envelope;
import com.graalmail.p2p.transport.P2PTransport.PeerConnection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link PeerNetworkService} over real TCP (spec sections 6, 7, 9): dials
 * configured mailbox peers through a {@link ServingTransport}, exchanges
 * GPMP store/fetch operations over {@link GpmpChannel}, and serves a local
 * {@link MailboxPeer} on inbound connections.
 *
 * <p>Peer signing keys use trust-on-first-use, pinned per endpoint: the first
 * contact fetches the key with {@code PEER_ANNOUNCE} and pins it; later
 * receipts verify against the pin and a changed key fails closed. Operators
 * running serious mailbox peers should additionally confirm pins out-of-band
 * (deployment guide, section 5). Unknown or unreachable peers report
 * {@code PEER_UNREACHABLE} — delivery is never claimed on a failed dial
 * (spec 6.6, 7.3).
 *
 * <p>Connections are dialled per request with bounded timeouts (spec 7.3) and
 * closed afterwards; there is no connection pooling in this revision.
 */
public class TcpPeerNetworkService implements PeerNetworkService {
    static final long REQUEST_TIMEOUT_MILLIS = 30_000;
    static final int MAX_KEY_BYTES = 256;
    static final int MAX_CONTENT_ID_CHARS = 256;

    private final ServingTransport transport;
    private final String networkId;
    private final List<PeerAddress> mailboxPeers;
    private final Map<String, byte[]> keyPins = new ConcurrentHashMap<>();
    private final Map<PeerConnection, GpmpChannel> inboundChannels = new ConcurrentHashMap<>();
    private volatile MailboxPeer local;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TcpPeerNetworkService(ServingTransport transport, String networkId,
                                 List<PeerAddress> mailboxPeers) {
        if (transport == null) throw new IllegalArgumentException("transport required");
        if (networkId == null || networkId.isBlank()) throw new IllegalArgumentException("networkId required");
        this.transport = transport;
        this.networkId = networkId;
        this.mailboxPeers = mailboxPeers == null ? List.of() : List.copyOf(mailboxPeers);
    }

    /**
     * Serves a local mailbox peer on inbound connections (spec 6.2, 9).
     * Must be called before {@link #start}. Dial-out-only nodes (Android)
     * never call this.
     */
    public void serveLocal(MailboxPeer peer) {
        if (peer == null) throw new IllegalArgumentException("mailbox peer required");
        if (running.get()) throw new IllegalStateException("already started");
        this.local = peer;
        transport.setInboundHandler(this::onInbound);
    }

    private void onInbound(PeerAddress remote, PeerConnection connection, byte[] frame) {
        try {
            inboundChannels.computeIfAbsent(connection, conn -> {
                GpmpChannel channel = new GpmpChannel(conn, networkId, REQUEST_TIMEOUT_MILLIS);
                channel.setRequestHandler(this::handleRequest);
                channel.start();
                return channel;
            });
        } catch (RuntimeException ignored) {
            // A broken inbound connection must not disturb the accept loop.
        }
    }

    private CompletionStage<byte[]> handleRequest(GpmpOperation operation, byte[] payload) {
        MailboxPeer peer = local;
        if (peer == null) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.CONFIGURATION_ERROR,
                    "mailbox service not configured", false, false, false));
        }
        try {
            return switch (operation) {
                case STORE_MESSAGE -> {
                    Envelope envelope = Envelope.fromBytes(payload);
                    yield peer.store(envelope).thenApply(StorageReceipt::toBytes);
                }
                case FETCH_MESSAGE -> {
                    String contentId = new String(payload == null ? new byte[0] : payload,
                            StandardCharsets.UTF_8);
                    validateContentId(contentId);
                    yield peer.fetch(contentId).thenApply(Envelope::toBytes);
                }
                case PEER_ANNOUNCE -> CompletableFuture.completedFuture(
                        peer.signingKey().getPublic().getEncoded());
                case HEARTBEAT -> CompletableFuture.completedFuture(new byte[0]);
                case DELETE_REQUEST -> {
                    // Best-effort across replicas (spec 9.4): compliant peers
                    // delete, malicious ones may not — never guaranteed.
                    String contentId = new String(payload == null ? new byte[0] : payload,
                            StandardCharsets.UTF_8);
                    validateContentId(contentId);
                    try {
                        peer.store().delete(contentId);
                    } catch (RuntimeException ignored) {
                    }
                    yield CompletableFuture.completedFuture(new byte[0]);
                }
                default -> CompletableFuture.failedFuture(new PolyMailException(
                        ErrorCode.INVALID_MESSAGE, "unsupported operation: " + operation,
                        false, false, false));
            };
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Void> start() {
        return transport.start().thenRun(() -> running.set(true));
    }

    @Override
    public CompletionStage<Void> stop() {
        running.set(false);
        for (GpmpChannel channel : inboundChannels.values()) {
            try {
                channel.close();
            } catch (Exception ignored) {
            }
        }
        inboundChannels.clear();
        return transport.stop();
    }

    @Override
    public NetworkStatus getStatus() {
        return transport.getStatus();
    }

    @Override
    public CompletionStage<StorageReceipt> storeMessage(PeerAddress peer, Envelope envelope) {
        byte[] wire;
        try {
            wire = envelope.toBytes();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return dial(peer).thenCompose(channel ->
                channel.request(GpmpOperation.STORE_MESSAGE, wire, REQUEST_TIMEOUT_MILLIS)
                        .whenComplete((payload, err) -> channel.close())
                        .thenApply(StorageReceipt::fromBytes));
    }

    @Override
    public CompletionStage<Envelope> fetchMessage(PeerAddress peer, String contentId) {
        try {
            validateContentId(contentId);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return dial(peer).thenCompose(channel ->
                channel.request(GpmpOperation.FETCH_MESSAGE,
                                contentId.getBytes(StandardCharsets.UTF_8), REQUEST_TIMEOUT_MILLIS)
                        .whenComplete((payload, err) -> channel.close())
                        .thenApply(Envelope::fromBytes));
    }

    @Override
    public CompletionStage<List<PeerAddress>> discoverPeers() {
        List<PeerAddress> peers = new ArrayList<>(mailboxPeers);
        if (local != null) {
            transport.localAddress().ifPresent(self -> {
                if (!peers.contains(self)) peers.add(self);
            });
        }
        return CompletableFuture.completedFuture(List.copyOf(peers));
    }

    @Override
    public void registerPeer(PeerAddress address, MailboxPeer peer) {
        if (address == null || peer == null
                || peer.signingKey() == null || peer.signingKey().getPublic() == null) {
            return;
        }
        pinKey(address, peer.signingKey().getPublic().getEncoded());
    }

    @Override
    public CompletionStage<java.util.Optional<byte[]>> peerSigningKey(PeerAddress peer) {
        byte[] pinned = peer == null ? null : keyPins.get(endpoint(peer));
        if (pinned != null) return CompletableFuture.completedFuture(java.util.Optional.of(pinned));
        if (peer == null) return CompletableFuture.completedFuture(java.util.Optional.empty());
        return dial(peer).thenCompose(channel ->
                channel.request(GpmpOperation.PEER_ANNOUNCE, new byte[0], REQUEST_TIMEOUT_MILLIS)
                        .whenComplete((payload, err) -> channel.close())
                        .thenApply(key -> {
                            validateKey(key);
                            pinKey(peer, key);
                            return java.util.Optional.of(key);
                        }))
                .exceptionally(err -> java.util.Optional.empty());
    }

    private void pinKey(PeerAddress peer, byte[] key) {
        validateKey(key);
        keyPins.put(endpoint(peer), key.clone());
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length == 0 || key.length > MAX_KEY_BYTES) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "invalid peer signing key",
                    false, false, false);
        }
    }

    private static void validateContentId(String contentId) {
        if (contentId == null || contentId.isBlank() || contentId.length() > MAX_CONTENT_ID_CHARS) {
            throw new PolyMailException(ErrorCode.INVALID_MESSAGE, "invalid content id",
                    false, false, false);
        }
    }

    private static String endpoint(PeerAddress peer) {
        return peer.host() + ":" + peer.port();
    }

    /**
     * Dials the peer and opens a request channel. Callers run their request
     * and close the returned channel in a {@code whenComplete} step so every
     * dialled connection is released.
     */
    private CompletionStage<GpmpChannel> dial(PeerAddress peer) {
        if (peer == null) {
            return CompletableFuture.failedFuture(new PolyMailException(ErrorCode.PEER_UNREACHABLE,
                    "no peer address", true, false, true));
        }
        CompletionStage<PeerConnection> connected;
        try {
            connected = transport.connect(peer);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return connected.thenApply(conn -> {
            GpmpChannel channel = new GpmpChannel(conn, networkId, REQUEST_TIMEOUT_MILLIS);
            channel.start();
            return channel;
        });
    }
}
