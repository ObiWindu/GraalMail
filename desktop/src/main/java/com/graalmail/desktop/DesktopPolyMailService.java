/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.PolyMailConfig;
import com.graalmail.p2p.PolyMailNode;
import com.graalmail.p2p.blockchain.InMemoryCursorStore;
import com.graalmail.p2p.blockchain.Web3jPolygonNotificationService;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.delivery.InMemoryDeliveryStore;
import com.graalmail.p2p.identity.InMemoryIdentityService;
import com.graalmail.p2p.mailbox.InMemoryContentStore;
import com.graalmail.p2p.mailbox.InMemoryInboxRepository;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.transport.NioTcpTransport;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.SqlMailStore;
import com.graalmail.storage.SqlPolyMailStore;
import com.graalmail.storage.SqlSchema;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * Desktop PolyMail background service (spec sections 15.1, 15.3).
 *
 * <p>Owns the shared {@link PolyMailNode}: network I/O runs on a managed
 * background executor, never on the JavaFX thread, and shutdown persists
 * outstanding operations safely. Controllers observe delivery states through
 * node listeners and render them with section-15.2 labels; no blockchain RPC,
 * cryptography, or peer routing lives in the UI layer.
 *
 * <p>Lifecycle hooks: {@link #start} on application startup / wallet unlock,
 * {@link #scan} on RPC reconnect or user refresh, {@link #stop} on application
 * shutdown.
 */
public final class DesktopPolyMailService implements AutoCloseable {
    private static final Logger LOG = Log.of(DesktopPolyMailService.class);

    private final PolyMailNode node;
    private final NioTcpTransport transport;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "polymail-desktop");
        thread.setDaemon(true);
        return thread;
    });
    private final SqlPolyMailStore durableStore;
    private final JdbcSqlDatabase database;

    private DesktopPolyMailService(PolyMailNode node, NioTcpTransport transport,
                                   SqlPolyMailStore durableStore, JdbcSqlDatabase database) {
        this.node = node;
        this.transport = transport;
        this.durableStore = durableStore;
        this.database = database;
    }

    /**
     * Creates the service against the desktop data directory. The durable
     * delivery store shares the application's SQLite database (migrated to the
     * PolyMail schema); memory-only components suit a first release and are
     * replaced by persistent peers/identity sync as those land.
     */
    public static DesktopPolyMailService create(AppPaths paths, PolyMailConfig config) throws Exception {
        java.nio.file.Files.createDirectories(paths.baseDirectory());
        JdbcSqlDatabase database = new JdbcSqlDatabase("jdbc:sqlite:" + paths.databaseFile());
        SqlSchema.migrate(database);
        SqlPolyMailStore durableStore = new SqlPolyMailStore(database);

        NioTcpTransport transport = new NioTcpTransport(config.listenPort(),
                config.networkId().code(), "desktop-" + java.util.UUID.randomUUID());
        InMemoryPeerNetworkService peerNetwork = new InMemoryPeerNetworkService();
        // The TCP transport carries GPMP frames; the in-memory peer registry
        // routes content operations until DHT discovery lands.
        PolyMailNode node = PolyMailNode.builder()
                .identities(new InMemoryIdentityService())
                .crypto(new DefaultEncryptionService())
                .peerNetwork(peerNetwork)
                .polygon(new Web3jPolygonNotificationService(config.rpcUrls().get(0), config.contractAddress(), config.chainId()))
                .inbox(new InMemoryInboxRepository())
                .mailboxPeers(List.of())
                .config(config)
                .deliveryStore(durableStore)
                .cursors(durableStore)
                .build();
        LOG.info("PolyMail desktop service configured for network {}", config.networkId());
        return new DesktopPolyMailService(node, transport, durableStore, database);
    }

    public PolyMailNode node() { return node; }
    public SqlPolyMailStore durableStore() { return durableStore; }

    /** Application startup / wallet unlock hook. */
    public CompletionStage<Void> start() {
        return CompletableFuture.runAsync(() -> node.start().toCompletableFuture().join(), background);
    }

    /** Manual refresh / RPC reconnect hook: one foreground scan pass. */
    public CompletionStage<com.graalmail.p2p.delivery.ScanReport> scan() {
        return CompletableFuture.supplyAsync(() -> node.scanOnce().toCompletableFuture().join(), background);
    }

    /** Application shutdown hook: stops the network node, then closes the database. */
    @Override
    public void close() {
        try {
            node.stop().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("PolyMail node did not stop cleanly: {}", e.getMessage());
        }
        try {
            transport.stop().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.debug("Transport stop: {}", e.getMessage());
        }
        background.shutdown();
        try {
            database.close();
        } catch (Exception e) {
            LOG.warn("PolyMail database could not be closed cleanly: {}", e.getMessage());
        }
    }
}
