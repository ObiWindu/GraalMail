/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal scripted HTTP server for offline Proton API tests.
 *
 * <p>Serves queued responses in order and records every request received.</p>
 */
final class StubProtonHttp implements AutoCloseable {

    record Request(String method, String path, Map<String, String> headers, String body) {
    }

    record Response(int status, String body) {
    }

    private final ServerSocket server;
    private final Thread thread;
    private final BlockingQueue<Response> script = new LinkedBlockingQueue<>();
    private final List<Request> received = new ArrayList<>();
    private volatile boolean closed;

    StubProtonHttp() throws Exception {
        server = new ServerSocket(0);
        thread = new Thread(this::serve, "stub-proton-http");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    void respond(int status, String body) {
        script.add(new Response(status, body));
    }

    List<Request> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    void reset() {
        synchronized (received) {
            received.clear();
        }
    }

    Request awaitRequest() throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (received) {
                if (!received.isEmpty()) {
                    return received.get(received.size() - 1);
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Timed out waiting for a request");
    }

    private void serve() {
        try {
            while (!closed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5_000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = in.readLine();
                    if (requestLine == null) {
                        continue;
                    }
                    String[] parts = requestLine.split(" ", 3);
                    Map<String, String> headers = new LinkedHashMap<>();
                    String line;
                    int contentLength = 0;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String name = line.substring(0, colon).strip();
                            String value = line.substring(colon + 1).strip();
                            headers.put(name, value);
                            if (name.equalsIgnoreCase("Content-Length")) {
                                contentLength = Integer.parseInt(value);
                            }
                        }
                    }
                    char[] bodyChars = new char[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int n = in.read(bodyChars, read, contentLength - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    String path = parts.length > 1 ? parts[1] : "/";
                    synchronized (received) {
                        received.add(new Request(parts[0], path, headers, new String(bodyChars, 0, read)));
                    }
                    Response next = script.poll(5, TimeUnit.SECONDS);
                    if (next == null) {
                        next = new Response(500, "{\"Code\":500,\"Error\":\"No scripted response\"}");
                    }
                    byte[] payload = next.body().getBytes(StandardCharsets.UTF_8);
                    String response = "HTTP/1.1 " + next.status() + " Test\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + payload.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    OutputStream out = socket.getOutputStream();
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.write(payload);
                    out.flush();
                } catch (Exception ignored) {
                    if (closed) {
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
            // test stub only
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
        server.close();
    }
}
