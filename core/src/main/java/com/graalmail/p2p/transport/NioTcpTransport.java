package com.graalmail.p2p.transport;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Real TCP transport behind the {@link P2PTransport} interface (spec section 3.1:
 * the protocol is owned by PolyMail; the networking library sits behind the
 * interface and can be swapped, including for a vetted libp2p implementation).
 *
 * <p>Wire format: {@code int32 length || frame bytes}. Every connection opens
 * with a {@code HELLO} handshake carrying the protocol version and network id;
 * mismatches are rejected before any protocol traffic flows, so test-network
 * peers can never talk to production peers (spec section 6.3).
 *
 * <p>NAT traversal (spec section 6.6): direct connections are attempted first
 * with a bounded timeout. Where direct dialling fails, callers keep the message
 * queued and may use recipient-operated reachable mailbox nodes as relays; see
 * the deployment guide for relay operation. This transport reports
 * {@code PEER_UNREACHABLE} rather than ever claiming delivery on a failed dial.
 */
public class NioTcpTransport implements ServingTransport {
    static final int MAX_FRAME_BYTES = ProtocolConstants.MAX_MESSAGE_BYTES + 1024;

    private final int listenPort;
    private final String networkId;
    private final String peerId;
    private final int connectTimeoutMillis;
    private final int handshakeTimeoutMillis;

    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "polymail-tcp");
        t.setDaemon(true);
        return t;
    });
    private final Set<NioConnection> open = ConcurrentHashMap.newKeySet();
    private volatile ServingTransport.InboundHandler inboundHandler;
    private volatile ServerSocketChannel server;
    private volatile boolean running;
    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean(false);

    public NioTcpTransport(int listenPort, String networkId, String peerId) {
        this(listenPort, networkId, peerId, 10_000, 10_000);
    }

    public NioTcpTransport(int listenPort, String networkId, String peerId,
                           int connectTimeoutMillis, int handshakeTimeoutMillis) {
        this.listenPort = listenPort;
        this.networkId = networkId;
        this.peerId = peerId;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    }

    @Override
    public void setInboundHandler(ServingTransport.InboundHandler handler) {
        this.inboundHandler = handler;
    }

    @Override
    public CompletionStage<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (listenPort >= 0) {
                    server = ServerSocketChannel.open();
                    server.bind(new InetSocketAddress(listenPort));
                    workers.submit(this::acceptLoop);
                }
                running = true;
            } catch (IOException e) {
                throw new PolyMailException(ErrorCode.NETWORK_UNAVAILABLE, "cannot bind TCP port", e);
            }
        }, workers);
    }

    @Override
    public CompletionStage<Void> stop() {
        // Idempotent: node shutdown and service shutdown both stop the
        // transport, and a second stop must not fail on the shut-down pool.
        if (!stopped.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            running = false;
            for (NioConnection c : open) {
                try { c.close(); } catch (Exception ignored) {}
            }
            open.clear();
            try {
                if (server != null) server.close();
            } catch (IOException ignored) {}
        }, workers).thenRun(workers::shutdown);
    }

    @Override
    public void close() {
        try {
            stop().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    @Override
    public NetworkStatus getStatus() {
        return new NetworkStatus(running, open.size(), "tcp:" + listenPort);
    }

    /** Bound port, or -1 when client-only (constructed with a negative port). */
    public int boundPort() {
        try {
            return server == null ? -1 : ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public java.util.Optional<PeerAddress> localAddress() {
        int port = boundPort();
        if (port <= 0) return java.util.Optional.empty();
        return java.util.Optional.of(new PeerAddress("127.0.0.1", port, new PeerId(peerId)));
    }

    @Override
    public CompletionStage<PeerConnection> connect(PeerAddress address) {
        return CompletableFuture.supplyAsync(() -> {
            SocketChannel channel;
            try {
                channel = SocketChannel.open();
                channel.socket().connect(new InetSocketAddress(address.host(), address.port()), connectTimeoutMillis);
                channel.socket().setSoTimeout(handshakeTimeoutMillis);
            } catch (IOException e) {
                throw new PolyMailException(ErrorCode.PEER_UNREACHABLE,
                        "cannot dial " + address.host() + ":" + address.port(), e);
            }
            try {
                writeFrame(channel, hello().getBytes(StandardCharsets.UTF_8), handshakeTimeoutMillis);
                String reply = new String(readFrame(channel, handshakeTimeoutMillis), StandardCharsets.UTF_8);
                if (!reply.startsWith("HELLO-ACK " + ProtocolConstants.PROTOCOL_VERSION + " " + networkId + " ")) {
                    try { channel.close(); } catch (IOException ignored) {}
                    throw new PolyMailException(ErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                            "handshake rejected: " + reply);
                }
            } catch (PolyMailException e) {
                throw e;
            } catch (Exception e) {
                try { channel.close(); } catch (IOException ignored) {}
                throw new PolyMailException(ErrorCode.TIMEOUT, "handshake timed out", e);
            }
            NioConnection conn = new NioConnection(channel, true);
            open.add(conn);
            return conn;
        }, workers);
    }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel channel = server.accept();
                workers.submit(() -> serve(channel));
            } catch (IOException e) {
                if (running) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    private void serve(SocketChannel channel) {
        try {
            channel.socket().setSoTimeout(handshakeTimeoutMillis);
            String hello = new String(readFrame(channel, handshakeTimeoutMillis), StandardCharsets.UTF_8);
            String[] parts = hello.split(" ", 4);
            if (parts.length != 4 || !"HELLO".equals(parts[0])
                    || !ProtocolConstants.PROTOCOL_VERSION.equals(parts[1])
                    || !networkId.equals(parts[2])) {
                writeFrame(channel, ("HELLO-ERROR unsupported " + hello).getBytes(StandardCharsets.UTF_8),
                        handshakeTimeoutMillis);
                channel.close();
                return;
            }
            writeFrame(channel,
                    ("HELLO-ACK " + ProtocolConstants.PROTOCOL_VERSION + " " + networkId + " " + peerId)
                            .getBytes(StandardCharsets.UTF_8),
                    handshakeTimeoutMillis);
            NioConnection conn = new NioConnection(channel, false);
            open.add(conn);
            ServingTransport.InboundHandler handler = inboundHandler;
            String remotePeer = parts[3];
            try {
                while (running && channel.isOpen()) {
                    byte[] frame = readFrame(channel, 0);
                    if (handler != null) {
                        PeerAddress remote;
                        try {
                            java.net.SocketAddress addr = channel.getRemoteAddress();
                            String host = addr instanceof InetSocketAddress isa ? isa.getHostString() : addr.toString();
                            int port = addr instanceof InetSocketAddress isa ? isa.getPort() : 0;
                            remote = new PeerAddress(host, Math.max(port, 1), new PeerId(remotePeer));
                        } catch (Exception e) {
                            remote = new PeerAddress("unknown", 1, new PeerId(remotePeer));
                        }
                        handler.onMessage(remote, conn, frame);
                    }
                    conn.deliver(frame);
                }
            } catch (Exception e) {
                conn.fail(e);
            } finally {
                open.remove(conn);
                try { conn.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    private String hello() {
        return "HELLO " + ProtocolConstants.PROTOCOL_VERSION + " " + networkId + " " + peerId;
    }

    static void writeFrame(SocketChannel channel, byte[] frame, int timeoutMillis) throws IOException {
        if (frame.length > MAX_FRAME_BYTES) throw new IOException("frame too large");
        long deadline = timeoutMillis > 0 ? System.currentTimeMillis() + timeoutMillis : Long.MAX_VALUE;
        ByteBuffer out = ByteBuffer.allocate(4 + frame.length);
        out.putInt(frame.length);
        out.put(frame);
        out.flip();
        while (out.hasRemaining()) {
            if (System.currentTimeMillis() > deadline) throw new IOException("write timed out");
            channel.write(out);
        }
    }

    static byte[] readFrame(SocketChannel channel, int timeoutMillis) throws IOException {
        ByteBuffer len = ByteBuffer.allocate(4);
        fill(channel, len, timeoutMillis);
        len.flip();
        int size = len.getInt();
        if (size < 0 || size > MAX_FRAME_BYTES) throw new IOException("invalid frame size: " + size);
        ByteBuffer body = ByteBuffer.allocate(size);
        fill(channel, body, timeoutMillis);
        return body.array();
    }

    private static void fill(SocketChannel channel, ByteBuffer buf, int timeoutMillis) throws IOException {
        long deadline = timeoutMillis > 0 ? System.currentTimeMillis() + timeoutMillis : Long.MAX_VALUE;
        channel.socket().setSoTimeout(timeoutMillis > 0 ? Math.min(timeoutMillis, 30_000) : 0);
        while (buf.hasRemaining()) {
            if (System.currentTimeMillis() > deadline) throw new IOException("read timed out");
            int n;
            try {
                n = channel.read(buf);
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            if (n == -1) throw new IOException("connection closed");
        }
    }

    final class NioConnection implements PeerConnection {
        private final SocketChannel channel;
        private final BlockingQueue<CompletableFuture<byte[]>> waiters = new LinkedBlockingQueue<>();
        private final BlockingQueue<byte[]> backlog = new LinkedBlockingQueue<>();

        NioConnection(SocketChannel channel, boolean startReader) {
            this.channel = channel;
            if (startReader) workers.submit(this::readLoop);
        }

        /** Frames read by the server-side serve loop (already consumed from the socket). */
        void deliver(byte[] frame) {
            CompletableFuture<byte[]> waiter = waiters.poll();
            if (waiter != null) waiter.complete(frame);
            else backlog.offer(frame);
        }

        void fail(Throwable err) {
            CompletableFuture<byte[]> waiter;
            while ((waiter = waiters.poll()) != null) {
                waiter.completeExceptionally(err instanceof Exception ex ? ex : new IOException(err));
            }
        }

        private void readLoop() {
            try {
                while (channel.isOpen()) {
                    byte[] frame = readFrame(channel, 0);
                    deliver(frame);
                }
            } catch (Exception e) {
                fail(e);
            } finally {
                open.remove(this);
            }
        }

        @Override
        public CompletionStage<Void> send(byte[] data) {
            return CompletableFuture.runAsync(() -> {
                try {
                    synchronized (channel) {
                        writeFrame(channel, data, handshakeTimeoutMillis);
                    }
                } catch (IOException e) {
                    throw new PolyMailException(ErrorCode.PEER_UNREACHABLE, "send failed", e);
                }
            }, workers);
        }

        @Override
        public CompletionStage<byte[]> receive() {
            byte[] ready = backlog.poll();
            if (ready != null) return CompletableFuture.completedFuture(ready);
            CompletableFuture<byte[]> waiter = new CompletableFuture<>();
            waiters.offer(waiter);
            byte[] raced = backlog.poll();
            if (raced != null && waiters.remove(waiter)) return CompletableFuture.completedFuture(raced);
            return waiter;
        }

        @Override
        public void close() throws IOException {
            channel.close();
            fail(new IOException("connection closed"));
        }
    }
}
