/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Default transport for the Proton API over {@link HttpURLConnection}.
 *
 * <p>Sends the client identification headers Proton requires on every call.
 * Authentication headers are supplied per request by the caller.</p>
 */
public final class DefaultProtonHttpTransport implements ProtonHttpTransport {

    /** Current API host of the Proton web clients. Overridable via settings. */
    public static final String DEFAULT_BASE_URL = "https://mail-api.proton.me";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    private final String baseUrl;
    private final String appVersion;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public DefaultProtonHttpTransport() {
        this(DEFAULT_BASE_URL, ProtonApiClient.DEFAULT_APP_VERSION, 15_000, 30_000);
    }

    public DefaultProtonHttpTransport(String baseUrl, String appVersion,
                                      int connectTimeoutMillis, int readTimeoutMillis) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.appVersion = appVersion == null || appVersion.isBlank()
                ? ProtonApiClient.DEFAULT_APP_VERSION : appVersion;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public ProtonHttpResponse execute(ProtonHttpRequest request) throws IOException {
        String target = baseUrl + request.path()
                + (request.query().isBlank() ? "" : "?" + request.query());
        HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
        try {
            connection.setRequestMethod(request.method());
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Pm-Appversion", appVersion);
            connection.setRequestProperty("X-Pm-Apiversion", "3");
            for (var header : request.headers().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (request.body() != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = request.body().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new ProtonHttpResponse(status, body);
        } finally {
            connection.disconnect();
        }
    }
}
