/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import com.graalmail.core.error.ProviderException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The default {@link GraphHttpTransport}, implemented with
 * {@code HttpURLConnection} so that it works on the desktop JVM and on
 * Android without additional dependencies.
 */
public final class DefaultGraphHttpTransport implements GraphHttpTransport {

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /**
     * Creates a transport with the default timeouts.
     */
    public DefaultGraphHttpTransport() {
        this(15_000, 30_000);
    }

    /**
     * Creates a transport.
     *
     * @param connectTimeoutMillis the connect timeout
     * @param readTimeoutMillis the read timeout
     */
    public DefaultGraphHttpTransport(int connectTimeoutMillis, int readTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public GraphHttpResponse execute(GraphHttpRequest request) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = URI.create(request.path()).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(request.method());
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (request.body() != null && !request.body().isEmpty()) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = request.body().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = connection.getResponseCode();
            byte[] raw = readBody(connection, status);
            String body = new String(raw, StandardCharsets.UTF_8);
            return new GraphHttpResponse(status, body, raw);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw ProviderException.network("Could not reach the Microsoft Graph service: "
                    + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readBody(HttpURLConnection connection, int status) {
        try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            if (in == null) {
                return new byte[0];
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
