/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import java.nio.charset.StandardCharsets;

/**
 * The response of a Microsoft Graph request.
 */
public final class GraphHttpResponse {

    private final int status;
    private final String body;
    private final byte[] content;

    /**
     * Creates a textual response.
     *
     * @param status the HTTP status code
     * @param body the response body, may be {@code null}
     */
    public GraphHttpResponse(int status, String body) {
        this(status, body, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Creates a response.
     *
     * @param status the HTTP status code
     * @param body the response body, may be {@code null}
     * @param content the raw response bytes
     */
    public GraphHttpResponse(int status, String body, byte[] content) {
        this.status = status;
        this.body = body == null ? "" : body;
        this.content = content == null ? new byte[0] : content;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    public byte[] content() {
        return content;
    }
}
