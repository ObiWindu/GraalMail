package com.graalmail.p2p.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.mailbox.InMemoryContentStore;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.mailbox.StorageReceipt;
import com.graalmail.p2p.protocol.Envelope;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The GPMP peer protocol over real TCP (spec 19.2): two nodes exchange
 * content through {@link TcpPeerNetworkService} on loopback — store with a
 * verified receipt, fetch the envelope back, and fail closed on every
 * abuse case. No in-memory shortcuts: every byte crosses a socket.
 */
class TcpPeerNetworkServiceTest {

    private static final EncryptionService CRYPTO = new DefaultEncryptionService();

    private record Fixture(TcpPeerNetworkService client, TcpPeerNetworkService serverNetwork,
                           PeerAddress serverAddress, KeyPair serverSigning) implements AutoCloseable {
        @Override
        public void close() {
            try {
                client.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            try {
                serverNetwork.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private static Fixture fixture() throws Exception {
        KeyPair signing = CRYPTO.createSigningKeyPair();
        NioTcpTransport serverTransport = new NioTcpTransport(0, "test", "mailbox-1");
        TcpPeerNetworkService serverNetwork =
                new TcpPeerNetworkService(serverTransport, "test", List.of());
        serverNetwork.serveLocal(new MailboxPeer("mailbox-1", new InMemoryContentStore(), signing));
        serverNetwork.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        int port = serverTransport.boundPort();
        NioTcpTransport clientTransport = new NioTcpTransport(-1, "test", "client-1");
        PeerAddress serverAddress = new PeerAddress("127.0.0.1", port, null);
        TcpPeerNetworkService client =
                new TcpPeerNetworkService(clientTransport, "test", List.of(serverAddress));
        client.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return new Fixture(client, serverNetwork, serverAddress, signing);
    }

    private static Envelope envelope(String messageId, long expiresAt) {
        byte[] ciphertext = ("ciphertext-for-" + messageId).getBytes(StandardCharsets.UTF_8);
        Envelope bare = new Envelope("GPMP/1", "test", messageId, new byte[32],
                ciphertext, new byte[]{1}, 1, 1, 1700000000L, expiresAt, new byte[8]);
        byte[] hash = CRYPTO.contentHash(bare.unsignedContentBytes());
        KeyPair sender = CRYPTO.createSigningKeyPair();
        byte[] signature = CRYPTO.sign(sender, new Envelope("GPMP/1", "test", messageId, hash,
                ciphertext, new byte[]{1}, 1, 1, 1700000000L, expiresAt, new byte[8]).signingBytes());
        return new Envelope("GPMP/1", "test", messageId, hash, ciphertext,
                new byte[]{1}, 1, 1, 1700000000L, expiresAt, signature);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void storeFetchRoundTripWithVerifiedReceipt() throws Exception {
        try (Fixture fixture = fixture()) {
            Envelope envelope = envelope("msg-tcp-001", System.currentTimeMillis() + 3_600_000);
            StorageReceipt receipt = fixture.client()
                    .storeMessage(fixture.serverAddress(), envelope)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertEquals("msg-tcp-001", receipt.messageId());
            assertEquals(hex(envelope.contentHash()), receipt.contentId());

            // The receipt verifies against the peer's TOFU-pinned key.
            byte[] key = fixture.client().peerSigningKey(fixture.serverAddress())
                    .toCompletableFuture().get(30, TimeUnit.SECONDS).orElseThrow();
            assertArrayEquals(fixture.serverSigning().getPublic().getEncoded(), key);
            assertTrue(CRYPTO.verify(key, receipt.signingBytes(), receipt.signature()));

            // The envelope comes back byte-identical.
            Envelope fetched = fixture.client()
                    .fetchMessage(fixture.serverAddress(), receipt.contentId())
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(envelope, fetched);

            // Discovery: the client sees its configured peer; the serving
            // node additionally lists itself (loopback dial works).
            List<PeerAddress> discovered = fixture.client().discoverPeers()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(List.of(fixture.serverAddress()), discovered);
            List<PeerAddress> serving = fixture.serverNetwork().discoverPeers()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(1, serving.size());
            assertEquals("127.0.0.1", serving.get(0).host());
        }
    }

    @Test
    void duplicateStoreIsIdempotent() throws Exception {
        try (Fixture fixture = fixture()) {
            Envelope envelope = envelope("msg-tcp-002", System.currentTimeMillis() + 3_600_000);
            StorageReceipt first = fixture.client()
                    .storeMessage(fixture.serverAddress(), envelope)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            StorageReceipt second = fixture.client()
                    .storeMessage(fixture.serverAddress(), envelope)
                    .toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(first.contentId(), second.contentId());
        }
    }

    @Test
    void unknownPeerFailsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (Fixture fixture = fixture()) {
            PeerAddress ghost = new PeerAddress("127.0.0.1", closedPort, null);
            try {
                fixture.client().storeMessage(ghost, envelope("msg-tcp-003",
                                System.currentTimeMillis() + 3_600_000))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                fail("expected PEER_UNREACHABLE");
            } catch (Exception e) {
                assertTrue(findCode(e, "PEER_UNREACHABLE"), "wrong error: " + e);
            }
        }
    }

    @Test
    void expiredEnvelopeRejected() throws Exception {
        try (Fixture fixture = fixture()) {
            try {
                fixture.client().storeMessage(fixture.serverAddress(),
                                envelope("msg-tcp-004", System.currentTimeMillis() - 1000))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                fail("expected rejection");
            } catch (Exception e) {
                assertTrue(e instanceof java.util.concurrent.ExecutionException);
            }
        }
    }

    @Test
    void missingContentFailsClosed() throws Exception {
        try (Fixture fixture = fixture()) {
            try {
                fixture.client().fetchMessage(fixture.serverAddress(), "0".repeat(64))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                fail("expected failure");
            } catch (Exception e) {
                assertTrue(e instanceof java.util.concurrent.ExecutionException);
            }
        }
    }

    @Test
    void networkMismatchRejectedAtHandshake() throws Exception {
        NioTcpTransport serverTransport = new NioTcpTransport(0, "test", "mailbox-2");
        TcpPeerNetworkService serverNetwork =
                new TcpPeerNetworkService(serverTransport, "test", List.of());
        serverNetwork.serveLocal(new MailboxPeer("mailbox-2", new InMemoryContentStore(),
                CRYPTO.createSigningKeyPair()));
        serverNetwork.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        int port = serverTransport.boundPort();
        NioTcpTransport prodTransport = new NioTcpTransport(-1, "prod", "client-2");
        TcpPeerNetworkService prodClient =
                new TcpPeerNetworkService(prodTransport, "prod",
                        List.of(new PeerAddress("127.0.0.1", port, null)));
        prodClient.start().toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            try {
                prodClient.storeMessage(new PeerAddress("127.0.0.1", port, null),
                                envelope("msg-tcp-005", System.currentTimeMillis() + 3_600_000))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                fail("expected handshake rejection");
            } catch (Exception e) {
                assertTrue(findCode(e, "UNSUPPORTED_PROTOCOL_VERSION")
                        || findCode(e, "PEER_UNREACHABLE"), "wrong error: " + e);
            }
        } finally {
            prodClient.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            serverNetwork.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void receiptCodecRoundTripsAndRejectsGarbage() {
        StorageReceipt receipt = new StorageReceipt("cid", "mid", "peer-1", 1000L, 2000L,
                "GPMP/1", new byte[]{1, 2, 3});
        assertEquals(receipt, StorageReceipt.fromBytes(receipt.toBytes()));
        assertArrayEquals(receipt.toBytes(), receipt.toBytes());
        try {
            StorageReceipt.fromBytes(new byte[]{0, 1, 2});
            fail("expected rejection");
        } catch (PolyMailException e) {
            // fail closed on truncated input
        }
        byte[] withTrailing = new byte[receipt.toBytes().length + 1];
        System.arraycopy(receipt.toBytes(), 0, withTrailing, 0, receipt.toBytes().length);
        try {
            StorageReceipt.fromBytes(withTrailing);
            fail("expected rejection");
        } catch (PolyMailException e) {
            // fail closed on trailing bytes
        }
    }

    @Test
    void malformedContentIdRejectedWithoutDialling() throws Exception {
        try (Fixture fixture = fixture()) {
            try {
                fixture.client().fetchMessage(fixture.serverAddress(), "")
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
                fail("expected rejection");
            } catch (Exception e) {
                assertTrue(findCode(e, "INVALID_MESSAGE"), "wrong error: " + e);
            }
        }
    }

    private static boolean findCode(Throwable err, String code) {
        Throwable current = err;
        while (current != null) {
            if (current instanceof PolyMailException poly
                    && poly.code().name().equals(code)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
