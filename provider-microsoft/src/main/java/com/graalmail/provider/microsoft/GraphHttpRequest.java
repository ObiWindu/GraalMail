/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A request against the Microsoft Graph web API.
 *
 * @param method the HTTP method
 * @param path the absolute request URL
 * @param query the query parameters, may be empty
 * @param body the JSON request body, may be {@code null}
 * @param headers additional headers, may be empty
 */
public record GraphHttpRequest(String method, String path, Map<String, String> query, String body,
                               Map<String, String> headers) {

    /**
     * Creates a request without additional headers.
     *
     * @param method the HTTP method
     * @param path the path below the Graph base URL
     * @param query the query parameters, may be {@code null}
     * @param body the JSON request body, may be {@code null}
     */
    public GraphHttpRequest(String method, String path, Map<String, String> query, String body) {
        this(method, path, query == null ? Map.of() : new LinkedHashMap<>(query), body, Map.of());
    }
}
