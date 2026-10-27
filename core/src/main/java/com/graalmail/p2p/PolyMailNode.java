package com.graalmail.p2p;

import com.graalmail.p2p.blockchain.CursorStore;
import com.graalmail.p2p.blockchain.PolygonNotificationService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.delivery.DeliveryStore;
import com.graalmail.p2p.delivery.NotificationScanner;
import com.graalmail.p2p.delivery.RecipientIdentity;
import com.graalmail.p2p.delivery.ScanReport;
import com.graalmail.p2p.identity.IdentityService;
import com.graalmail.p2p.mailbox.InboxRepository;
import com.graalmail.p2p.mailbox.StoragePolicy;
import com.graalmail.p2p.transport.NetworkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Shared PolyMail node facade used by the desktop and Android clients (spec
 * sections 2.1, 12, 15, 16). All network work is asynchronous; UI layers
 * observe state through {@link NodeListener} and must never block on these
 * calls from the UI thread.
 */
public class PolyMailNode implements AutoCloseable {
    private final PeerNetworkService peerNetwork;
    private final PolygonNotificationService polygon;
    private final DefaultDeliveryCoordinator coordinator;
    private final NotificationScanner scanner;
    private final CursorStore cursors;
    private final PolyMailConfig config;
    private final List<NodeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile RecipientIdentity recipient;
    private volatile List<String> senderCandidates = List.of();
    private volatile boolean started;

    private PolyMailNode(Builder builder) {
        this.peerNetwork = builder.peerNetwork;
        this.polygon = builder.polygon;
        this.config = builder.config;
        this.cursors = builder.cursors;
        this.coordinator = new DefaultDeliveryCoordinator(builder.identities, builder.crypto,
                builder.peerNetwork, builder.polygon, builder.inbox, builder.mailboxPeers,
                builder.config.networkId(), builder.config.contractAddress(), builder.senderWallet,
                builder.storagePolicy, builder.rateLimiter, builder.deliveryStore);
        this.scanner = new NotificationScanner(builder.polygon, this.coordinator,
                builder.config.chainId(), builder.config.contractAddress(), builder.config.confirmationDepth());
    }

    public void addListener(NodeListener listener) { listeners.add(listener); }
    public void removeListener(NodeListener listener) { listeners.remove(listener); }

    public void localIdentity(RecipientIdentity recipient, List<String> senderCandidates) {
        this.recipient = recipient;
        this.senderCandidates = List.copyOf(senderCandidates);
    }

    public CompletionStage<Void> start() {
        return peerNetwork.start().thenRun(() -> {
            started = true;
            emitStatus();
            coordinator.resumePending().whenComplete((n, err) -> {
                if (err != null) emitError("resume", err);
            });
        });
    }

    public CompletionStage<Void> stop() {
        started = false;
        return peerNetwork.stop();
    }

    public boolean isStarted() { return started; }

    public NetworkStatus networkStatus() { return peerNetwork.getStatus(); }

    public CompletionStage<SendResult> send(SendRequest request) {
        return coordinator.send(request).whenComplete((res, err) -> {
            if (err != null) emitError("send", err);
            emitStatus();
        });
    }

    /** One foreground scan pass; background schedulers call this periodically. */
    public CompletionStage<ScanReport> scanOnce() {
        RecipientIdentity me = recipient;
        if (me == null) {
            return CompletableFuture.failedFuture(new PolyMailException(
                    com.graalmail.core.error.ErrorCode.CONFIGURATION_ERROR, "local identity not configured"));
        }
        return scanner.scanOnce(cursors, me, senderCandidates).whenComplete((report, err) -> {
            if (err != null) emitError("scan", err);
            else {
                for (NodeListener l : listeners) {
                    try { l.onScan(report); } catch (Exception ignored) {}
                }
            }
            emitStatus();
        });
    }

    public List<String> inboxMessageIds() { return coordinator.inboxMessageIds(); }
    public boolean hasDelivered(String messageId) { return coordinator.hasDelivered(messageId); }
    public PolyMailConfig config() { return config; }

    private void emitStatus() {
        NetworkStatus status = peerNetwork.getStatus();
        for (NodeListener l : listeners) {
            try { l.onStatus(status); } catch (Exception ignored) {}
        }
    }

    private void emitError(String context, Throwable err) {
        for (NodeListener l : listeners) {
            try { l.onError(context, err); } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        try { stop().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception ignored) {}
    }

    public interface NodeListener {
        void onStatus(NetworkStatus status);
        void onScan(ScanReport report);
        void onError(String context, Throwable err);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private IdentityService identities;
        private EncryptionService crypto;
        private PeerNetworkService peerNetwork;
        private PolygonNotificationService polygon;
        private InboxRepository inbox;
        private List<PeerAddress> mailboxPeers = new ArrayList<>();
        private PolyMailConfig config;
        private String senderWallet = "";
        private StoragePolicy storagePolicy;
        private RateLimiter rateLimiter = new RateLimiter(60, 60_000);
        private DeliveryStore deliveryStore;
        private CursorStore cursors;

        public Builder identities(IdentityService v) { identities = v; return this; }
        public Builder crypto(EncryptionService v) { crypto = v; return this; }
        public Builder peerNetwork(PeerNetworkService v) { peerNetwork = v; return this; }
        public Builder polygon(PolygonNotificationService v) { polygon = v; return this; }
        public Builder inbox(InboxRepository v) { inbox = v; return this; }
        public Builder mailboxPeers(List<PeerAddress> v) { mailboxPeers = v; return this; }
        public Builder config(PolyMailConfig v) { config = v; return this; }
        public Builder senderWallet(String v) { senderWallet = v; return this; }
        public Builder storagePolicy(StoragePolicy v) { storagePolicy = v; return this; }
        public Builder rateLimiter(RateLimiter v) { rateLimiter = v; return this; }
        public Builder deliveryStore(DeliveryStore v) { deliveryStore = v; return this; }
        public Builder cursors(CursorStore v) { cursors = v; return this; }

        public PolyMailNode build() {
            if (identities == null || crypto == null || peerNetwork == null || polygon == null
                    || inbox == null || config == null || cursors == null) {
                throw new IllegalStateException("node requires identities, crypto, peerNetwork, polygon, inbox, config, cursors");
            }
            if (storagePolicy == null) storagePolicy = StoragePolicy.withReplicas(config.requiredReplicas());
            return new PolyMailNode(this);
        }
    }
}
