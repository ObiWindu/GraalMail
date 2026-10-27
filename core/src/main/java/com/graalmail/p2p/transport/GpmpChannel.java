package com.graalmail.p2p.transport;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.GpmpOperation;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;
import com.graalmail.p2p.protocol.GpmpMessage;
import com.graalmail.p2p.transport.P2PTransport.PeerConnection;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * Request/response multiplexer over one peer connection (spec section 7.3: every
 * operation carries an explicit request ID and timeout). Responses reuse the
 * request ID; a response with operation {@code ERROR} completes the call
 * exceptionally. A network-level acknowledgement is never treated as proof of
 * mail delivery (spec section 7.3).
 */
public class GpmpChannel implements AutoCloseable {
    private final PeerConnection connection;
    private final String networkId;
    private final long defaultTimeoutMillis;
    private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "polymail-gpmp-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile BiFunction<GpmpOperation, byte[], CompletionStage<byte[]>> requestHandler;
    private volatile boolean running = true;

    public GpmpChannel(PeerConnection connection, String networkId, long defaultTimeoutMillis) {
        this.connection = connection;
        this.networkId = networkId;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    public void setRequestHandler(BiFunction<GpmpOperation, byte[], CompletionStage<byte[]>> handler) {
        this.requestHandler = handler;
    }

    /** Starts the inbound dispatch loop. */
    public void start() {
        readNext();
    }

    private void readNext() {
        if (!running) return;
        connection.receive().whenComplete((frame, err) -> {
            if (err != null) {
                failAll(err);
                return;
            }
            try {
                dispatch(frame);
            } catch (Exception ignored) {
            }
            readNext();
        });
    }

    public CompletionStage<byte[]> request(GpmpOperation operation, byte[] payload) {
        return request(operation, payload, defaultTimeoutMillis);
    }

    public CompletionStage<byte[]> request(GpmpOperation operation, byte[] payload, long timeoutMillis) {
        String requestId = UUID.randomUUID().toString();
        GpmpMessage message = new GpmpMessage(ProtocolConstants.PROTOCOL_VERSION, networkId,
                operation, requestId, payload == null ? new byte[0] : payload, System.currentTimeMillis());
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pending.put(requestId, future);
        timers.schedule(() -> {
            if (pending.remove(requestId) != null) {
                future.completeExceptionally(new PolyMailException(ErrorCode.RETRIEVAL_TIMEOUT,
                        "request " + operation + " timed out", true, false, true));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        connection.send(message.toBytes()).exceptionally(err -> {
            if (pending.remove(requestId) != null) future.completeExceptionally(wrap(err));
            return null;
        });
        return future;
    }

    private void dispatch(byte[] frame) {
        GpmpMessage message;
        try {
            message = GpmpMessage.fromBytes(frame);
        } catch (PolyMailException e) {
            return; // malformed input is dropped, never trusted (spec 18)
        }
        if (!ProtocolConstants.PROTOCOL_VERSION.equals(message.protocolVersion())
                || !networkId.equals(message.networkId())) {
            return;
        }
        CompletableFuture<byte[]> future = pending.remove(message.requestId());
        if (future != null) {
            if (message.operation() == GpmpOperation.ERROR) {
                future.completeExceptionally(new PolyMailException(ErrorCode.INVALID_MESSAGE,
                        "remote error: " + new String(message.payload(), StandardCharsets.UTF_8)));
            } else {
                future.complete(message.payload());
            }
            return;
        }
        BiFunction<GpmpOperation, byte[], CompletionStage<byte[]>> handler = requestHandler;
        if (handler == null) {
            reply(message, GpmpOperation.ERROR, "no handler".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try {
            handler.apply(message.operation(), message.payload()).whenComplete((result, err) -> {
                if (err != null) reply(message, GpmpOperation.ERROR, errorText(err).getBytes(StandardCharsets.UTF_8));
                else reply(message, message.operation(), result == null ? new byte[0] : result);
            });
        } catch (Exception e) {
            reply(message, GpmpOperation.ERROR, errorText(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void reply(GpmpMessage request, GpmpOperation operation, byte[] payload) {
        GpmpMessage response = new GpmpMessage(ProtocolConstants.PROTOCOL_VERSION, networkId,
                operation, request.requestId(), payload, System.currentTimeMillis());
        connection.send(response.toBytes());
    }

    private static String errorText(Throwable err) {
        Throwable t = err;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof PolyMailException p) return p.code().name() + ": " + p.getMessage();
        if (t instanceof TimeoutException) return "TIMEOUT";
        return "INTERNAL: " + String.valueOf(t.getMessage());
    }

    private static RuntimeException wrap(Throwable err) {
        if (err instanceof RuntimeException re) return re;
        return new PolyMailException(ErrorCode.PEER_UNREACHABLE, "send failed", err);
    }

    private void failAll(Throwable err) {
        RuntimeException wrapped = wrap(err);
        for (CompletableFuture<byte[]> f : pending.values()) f.completeExceptionally(wrapped);
        pending.clear();
    }

    @Override
    public void close() {
        running = false;
        timers.shutdownNow();
        failAll(new PolyMailException(ErrorCode.CANCELLED, "channel closed"));
        try { connection.close(); } catch (Exception ignored) {}
    }
}
