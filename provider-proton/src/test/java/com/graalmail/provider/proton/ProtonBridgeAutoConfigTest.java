/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.core.error.ProviderException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtonBridgeAutoConfigTest {

    private static final ProtonBridgeAutoConfig CONFIG = new ProtonBridgeAutoConfig(500, 2_000);

    @Test
    void loopbackHostsAreAccepted() {
        assertEquals("127.0.0.1", ProtonBridgeAutoConfig.requireLoopback(""));
        assertEquals("127.0.0.1", ProtonBridgeAutoConfig.requireLoopback(null));
        assertEquals("127.0.0.1", ProtonBridgeAutoConfig.requireLoopback("127.0.0.1"));
        assertEquals("localhost", ProtonBridgeAutoConfig.requireLoopback("localhost"));
    }

    @Test
    void remoteHostsAreRejected() {
        ProviderException e = assertThrows(ProviderException.class,
                () -> ProtonBridgeAutoConfig.requireLoopback("imap.proton.me"));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, e.code());
    }

    @Test
    void endpointSettingsCarryAllBridgeKeys() {
        ProtonBridgeAutoConfig.Endpoint endpoint = new ProtonBridgeAutoConfig.Endpoint(
                "127.0.0.1", 1143, 1025, ProtonBridgeConfig.TlsMode.STARTTLS, "AABB");
        Map<String, String> settings = endpoint.settings("user@proton.me");
        assertEquals("127.0.0.1", settings.get(ProtonProvider.SETTING_HOST));
        assertEquals("1143", settings.get(ProtonProvider.SETTING_IMAP_PORT));
        assertEquals("1025", settings.get(ProtonProvider.SETTING_SMTP_PORT));
        assertEquals("user@proton.me", settings.get(ProtonProvider.SETTING_USERNAME));
        assertEquals("STARTTLS", settings.get(ProtonProvider.SETTING_TLS_MODE));
        assertEquals("AABB", settings.get(ProtonProvider.SETTING_FINGERPRINT));
    }

    @Test
    void missingLoginIsReported() {
        ProviderException e = assertThrows(ProviderException.class, () -> CONFIG.resolveAndVerify(
                "", null, null, null, "  ", "secret"));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, e.code());
    }

    @Test
    void missingPasswordIsReported() {
        ProviderException e = assertThrows(ProviderException.class, () -> CONFIG.resolveAndVerify(
                "", null, null, null, "user@proton.me", ""));
        assertEquals(ErrorCode.CONFIGURATION_ERROR, e.code());
    }

    @Test
    void unreachableBridgeIsReported() throws Exception {
        int closedPort = findClosedPort();
        ProviderException e = assertThrows(ProviderException.class, () -> CONFIG.resolveAndVerify(
                "127.0.0.1", closedPort, closedPort, ProtonBridgeConfig.TlsMode.STARTTLS,
                "user@proton.me", "secret"));
        assertEquals(ErrorCode.CONNECTION_REFUSED, e.code());
        assertTrue(e.userMessage().contains("Bridge is not running"));
    }

    @Test
    void smtpStartTlsEndpointIsDetected() throws Exception {
        try (StubSmtpServer server = new StubSmtpServer(true)) {
            assertTrue(CONFIG.isSmtpEndpoint("127.0.0.1", server.port(), true));
        }
    }

    @Test
    void smtpServerWithoutStartTlsIsRejectedForStartTlsMode() throws Exception {
        try (StubSmtpServer server = new StubSmtpServer(false)) {
            assertFalse(CONFIG.isSmtpEndpoint("127.0.0.1", server.port(), true));
        }
    }

    @Test
    void nonSmtpServiceIsRejected() throws Exception {
        try (StubGarbageServer server = new StubGarbageServer()) {
            assertFalse(CONFIG.isSmtpEndpoint("127.0.0.1", server.port(), true));
        }
        assertFalse(CONFIG.isSmtpEndpoint("127.0.0.1", findClosedPort(), true));
    }

    private static int findClosedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Minimal SMTP stub: greeting plus an EHLO response, with or without STARTTLS. */
    private static final class StubSmtpServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final boolean offerStartTls;

        StubSmtpServer(boolean offerStartTls) throws Exception {
            this.offerStartTls = offerStartTls;
            this.server = new ServerSocket(0);
            this.thread = new Thread(this::serve, "stub-smtp");
            this.thread.setDaemon(true);
            this.thread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            ready.countDown();
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5_000);
                OutputStream out = socket.getOutputStream();
                BufferedReader in = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                out.write("220 stub ESMTP ready\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                String line = in.readLine();
                if (line != null && line.toUpperCase().startsWith("EHLO")) {
                    out.write("250-stub greets\r\n".getBytes(StandardCharsets.US_ASCII));
                    if (offerStartTls) {
                        out.write("250 STARTTLS\r\n".getBytes(StandardCharsets.US_ASCII));
                    } else {
                        out.write("250 AUTH\r\n".getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                }
            } catch (Exception ignored) {
                // Test stub only.
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    /** Stub that answers with a non-SMTP greeting. */
    private static final class StubGarbageServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch ready = new CountDownLatch(1);
        private final AtomicInteger served = new AtomicInteger();

        StubGarbageServer() throws Exception {
            this.server = new ServerSocket(0);
            this.thread = new Thread(this::serve, "stub-garbage");
            this.thread.setDaemon(true);
            this.thread.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            ready.countDown();
            try (Socket socket = server.accept()) {
                served.incrementAndGet();
                socket.getOutputStream().write(
                        "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (Exception ignored) {
                // Test stub only.
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }
}
