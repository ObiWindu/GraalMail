package com.graalmail.p2p.transport;

import com.graalmail.p2p.GpmpOperation;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.transport.P2PTransport.PeerConnection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class NioTcpTransportTest {
    @Test
    void loopbackRequestResponse() throws Exception {
        try (NioTcpTransport server = new NioTcpTransport(0, "dev", "server-peer")) {
            server.setInboundHandler((remote, conn, frame) -> {
                GpmpChannel channel = new GpmpChannel(conn, "dev", 5_000);
                channel.setRequestHandler((op, payload) ->
                        CompletableFuture.completedFuture(("echo:" + new String(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
                channel.start();
            });
            server.start().toCompletableFuture().get();
            int port = server.boundPort();
            assertTrue(port > 0);

            try (NioTcpTransport client = new NioTcpTransport(0, "dev", "client-peer")) {
                client.start().toCompletableFuture().get();
                PeerConnection conn = client.connect(new PeerAddress("127.0.0.1", port, new PeerId("server-peer")))
                        .toCompletableFuture().get();
                try (GpmpChannel channel = new GpmpChannel(conn, "dev", 5_000)) {
                    channel.start();
                    byte[] response = channel.request(GpmpOperation.FETCH_MESSAGE, "cid-1".getBytes(StandardCharsets.UTF_8))
                            .toCompletableFuture().get();
                    assertEquals("echo:cid-1", new String(response, StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test
    void networkMismatchRejectedAtHandshake() throws Exception {
        try (NioTcpTransport server = new NioTcpTransport(0, "dev", "server-peer")) {
            server.start().toCompletableFuture().get();
            int port = server.boundPort();
            try (NioTcpTransport client = new NioTcpTransport(0, "prod", "client-peer")) {
                client.start().toCompletableFuture().get();
                try {
                    client.connect(new PeerAddress("127.0.0.1", port, new PeerId("server-peer")))
                            .toCompletableFuture().join();
                    fail("expected handshake rejection");
                } catch (Exception e) {
                    assertTrue(findPolyMail(e));
                }
            }
        }
    }

    @Test
    void requestTimesOutWhenPeerSilent() throws Exception {
        try (NioTcpTransport server = new NioTcpTransport(0, "dev", "server-peer")) {
            server.setInboundHandler((remote, conn, frame) -> {
                // Accept frames but never answer.
                GpmpChannel channel = new GpmpChannel(conn, "dev", 5_000);
                channel.start();
            });
            server.start().toCompletableFuture().get();
            int port = server.boundPort();
            try (NioTcpTransport client = new NioTcpTransport(0, "dev", "client-peer")) {
                client.start().toCompletableFuture().get();
                PeerConnection conn = client.connect(new PeerAddress("127.0.0.1", port, new PeerId("server-peer")))
                        .toCompletableFuture().get();
                try (GpmpChannel channel = new GpmpChannel(conn, "dev", 5_000)) {
                    channel.start();
                    try {
                        channel.request(GpmpOperation.FETCH_MESSAGE, new byte[0], 300)
                                .toCompletableFuture().join();
                        fail("expected timeout");
                    } catch (Exception e) {
                        assertTrue(findPolyMail(e));
                    }
                }
            }
        }
    }

    @Test
    void dialRefusedReportsUnreachable() {
        try (NioTcpTransport client = new NioTcpTransport(0, "dev", "client-peer")) {
            client.start().toCompletableFuture().join();
            try {
                client.connect(new PeerAddress("127.0.0.1", 1, new PeerId("nobody")))
                        .toCompletableFuture().join();
                fail("expected unreachable");
            } catch (Exception e) {
                assertTrue(findPolyMail(e));
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private static boolean findPolyMail(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof PolyMailException) return true;
            t = t.getCause();
        }
        return false;
    }
}
