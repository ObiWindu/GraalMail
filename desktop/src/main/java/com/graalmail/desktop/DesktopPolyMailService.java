/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.util.Log;
import com.graalmail.p2p.NetworkId;
import com.graalmail.p2p.PolyMailConfig;
import com.graalmail.p2p.PolyMailNode;
import com.graalmail.p2p.blockchain.Web3jPolygonNotificationService;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.identity.InMemoryIdentityService;
import com.graalmail.p2p.mailbox.InMemoryContentStore;
import com.graalmail.p2p.mailbox.InMemoryInboxRepository;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.transport.NioTcpTransport;
import com.graalmail.p2p.transport.TcpPeerNetworkService;
import com.graalmail.storage.JdbcSqlDatabase;
import com.graalmail.storage.SqlPolyMailStore;
import com.graalmail.storage.SqlSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Set;
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
    private final com.graalmail.p2p.discovery.PersistentPeerDiscoveryService discovery;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "polymail-desktop");
        thread.setDaemon(true);
        return thread;
    });
    private final SqlPolyMailStore durableStore;
    private final JdbcSqlDatabase database;

    private DesktopPolyMailService(PolyMailNode node, NioTcpTransport transport,
                                   com.graalmail.p2p.discovery.PersistentPeerDiscoveryService discovery,
                                   SqlPolyMailStore durableStore, JdbcSqlDatabase database) {
        this.node = node;
        this.transport = transport;
        this.discovery = discovery;
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
        return create(paths, config, List.of());
    }

    /**
     * Creates the service with explicit reachable mailbox peers (deployment
     * guide section 4: operate at least {@code requiredReplicas + 1}).
     * Unknown peers fail safe at send time with {@code PEER_UNREACHABLE};
     * delivery is never claimed on a failed dial.
     */
    public static DesktopPolyMailService create(AppPaths paths, PolyMailConfig config,
                                                List<com.graalmail.p2p.PeerAddress> mailboxPeers) throws Exception {
        Files.createDirectories(paths.baseDirectory());
        JdbcSqlDatabase database = new JdbcSqlDatabase("jdbc:sqlite:" + paths.databaseFile());
        SqlSchema.migrate(database);
        SqlPolyMailStore durableStore = new SqlPolyMailStore(database);

        String peerId = "desktop-" + java.util.UUID.randomUUID();
        NioTcpTransport transport = new NioTcpTransport(config.listenPort(),
                config.networkId().code(), peerId);
        TcpPeerNetworkService peerNetwork = new TcpPeerNetworkService(transport,
                config.networkId().code(), mailboxPeers == null ? List.of() : mailboxPeers);
        // This node also serves its mailbox (spec 6.2: one process may serve
        // several roles). The signing key is persisted so receipts stay
        // verifiable across restarts; content itself is memory-resident.
        peerNetwork.serveLocal(new MailboxPeer(peerId, new InMemoryContentStore(),
                mailboxSigningKey(paths.baseDirectory())));
        com.graalmail.p2p.discovery.PersistentPeerDiscoveryService discovery =
                new com.graalmail.p2p.discovery.PersistentPeerDiscoveryService(
                        paths.baseDirectory().resolve("polymail-peers.tsv"));
        String rpcUrl = config.rpcUrls().isEmpty() ? "" : config.rpcUrls().getFirst();
        PolyMailNode node = PolyMailNode.builder()
                .identities(new InMemoryIdentityService())
                .crypto(new DefaultEncryptionService())
                .peerNetwork(peerNetwork)
                .polygon(rpcUrl.isBlank()
                        ? new com.graalmail.p2p.blockchain.InMemoryPolygonNotificationService()
                        : new Web3jPolygonNotificationService(rpcUrl, config.contractAddress(), config.chainId()))
                .inbox(new InMemoryInboxRepository())
                .mailboxPeers(mailboxPeers == null ? List.of() : List.copyOf(mailboxPeers))
                .config(config)
                .deliveryStore(durableStore)
                .cursors(durableStore)
                .build();
        LOG.info("PolyMail desktop service configured for network {}", config.networkId());
        return new DesktopPolyMailService(node, transport, discovery, durableStore, database);
    }

    /**
     * Creates the service from the user's desktop settings. Returns empty
     * when PolyMail is disabled or unconfigured so ordinary mail sync is
     * unaffected; callers must not fail startup when this is empty.
     */
    public static java.util.Optional<DesktopPolyMailService> fromSettings(
            AppPaths paths, DesktopSettings settings) {
        if (!settings.polyMailEnabled()) {
            return java.util.Optional.empty();
        }
        String rpcRaw = settings.polyMailRpcUrls();
        List<String> rpcUrls = rpcRaw.isBlank() ? List.of()
                : java.util.Arrays.stream(rpcRaw.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        String contract = settings.polyMailContract();
        if (rpcUrls.isEmpty() || contract.isBlank()) {
            LOG.info("PolyMail is enabled but RPC endpoints or contract address are missing; node not started");
            return java.util.Optional.empty();
        }
        try {
            NetworkId network = NetworkId.fromCode(settings.polyMailNetwork());
            PolyMailConfig config = new PolyMailConfig(rpcUrls, contract,
                    settings.polyMailChainId(), network,
                    settings.polyMailListenPort(), 2, 2);
            List<com.graalmail.p2p.PeerAddress> mailboxPeers;
            try {
                mailboxPeers = com.graalmail.p2p.PeerAddress.parseList(settings.polyMailMailboxPeers());
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring malformed mailbox peers: {}", e.getMessage());
                mailboxPeers = List.of();
            }
            DesktopPolyMailService service = create(paths, config, mailboxPeers);
            String identity = settings.polyMailIdentity();
            if (!identity.isBlank()) {
                service.node().localIdentity(
                        new com.graalmail.p2p.delivery.RecipientIdentity(identity, new byte[0], new byte[0]),
                        List.of(identity));
            }
            return java.util.Optional.of(service);
        } catch (Exception e) {
            LOG.warn("PolyMail service could not be created: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    public PolyMailNode node() { return node; }
    public SqlPolyMailStore durableStore() { return durableStore; }
    public com.graalmail.p2p.discovery.PeerDiscoveryService discovery() { return discovery; }

    /** Application startup / wallet unlock hook. */
    public CompletionStage<Void> start() {
        try {
            discovery.expireStale(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOG.debug("Peer discovery cleanup: {}", e.getMessage());
        }
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
            // node.stop() delegates to the peer network, which stops the TCP
            // transport; NioTcpTransport.stop is idempotent regardless.
            node.stop().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("PolyMail node did not stop cleanly: {}", e.getMessage());
        }
        background.shutdown();
        try {
            database.close();
        } catch (Exception e) {
            LOG.warn("PolyMail database could not be closed cleanly: {}", e.getMessage());
        }
    }

    /**
     * Loads the mailbox signing key, generating and persisting it on first
     * use. The key identifies this mailbox peer to senders verifying storage
     * receipts, so it must survive restarts — unlike the peer id, which is
     * per-boot. Stored owner-only, next to the other key material.
     */
    static KeyPair mailboxSigningKey(Path baseDirectory) throws Exception {
        Path file = baseDirectory.resolve("polymail-mailbox-signing.key");
        com.graalmail.desktop.AppPaths paths = com.graalmail.desktop.AppPaths.of(baseDirectory);
        com.graalmail.security.store.SecretProtector protector =
                new com.graalmail.security.store.AesGcmProtector(
                        com.graalmail.desktop.DesktopSecretStore.resolvePassphrase(paths),
                        com.graalmail.desktop.DesktopSecretStore.readOrCreateSalt(paths.saltFile()));
        char[] passphrase = null;
        // resolvePassphrase returns char[]; keep a reference? It's already consumed by protector (copied internally? AesGcmProtector may store). We'll trust protector holds its own copy.
        if (Files.isRegularFile(file)) {
            byte[] stored = Files.readAllBytes(file);
            // Try sealed format first, then legacy plaintext for migration.
            try {
                return readSigningKey(protector.decrypt(stored));
            } catch (Exception sealedFailed) {
                try {
                    KeyPair legacy = readSigningKey(stored);
                    resealMailboxKey(file, legacy, protector);
                    return legacy;
                } catch (Exception ignored) {
                    LOG.warn("The mailbox signing key could not be read; generating a fresh one");
                }
            }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair fresh = generator.generateKeyPair();
        byte[] privateBytes = fresh.getPrivate().getEncoded();
        byte[] publicBytes = fresh.getPublic().getEncoded();
        java.nio.ByteBuffer framed = java.nio.ByteBuffer.allocate(4 + privateBytes.length + publicBytes.length);
        framed.putInt(privateBytes.length);
        framed.put(privateBytes);
        framed.put(publicBytes);
        byte[] sealed = protector.encrypt(framed.array());
        Files.write(file, sealed,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems (Windows) have no owner-only mode.
        }
        return fresh;
    }

    private static void resealMailboxKey(Path file, KeyPair key, com.graalmail.security.store.SecretProtector protector) throws Exception {
        byte[] privateBytes = key.getPrivate().getEncoded();
        byte[] publicBytes = key.getPublic().getEncoded();
        java.nio.ByteBuffer framed = java.nio.ByteBuffer.allocate(4 + privateBytes.length + publicBytes.length);
        framed.putInt(privateBytes.length);
        framed.put(privateBytes);
        framed.put(publicBytes);
        byte[] sealed = protector.encrypt(framed.array());
        Files.write(file, sealed,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static KeyPair readSigningKey(byte[] framed)
            throws java.security.GeneralSecurityException, java.io.IOException {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(framed);
        int privateLength = buffer.getInt();
        if (privateLength <= 0 || privateLength > framed.length) {
            throw new java.io.IOException("corrupt mailbox signing key");
        }
        byte[] privateBytes = new byte[privateLength];
        buffer.get(privateBytes);
        byte[] publicBytes = new byte[buffer.remaining()];
        buffer.get(publicBytes);
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicBytes));
        return new KeyPair(publicKey, privateKey);
    }
}
