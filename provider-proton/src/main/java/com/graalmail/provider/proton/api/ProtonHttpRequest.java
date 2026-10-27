/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.util.List;
import java.util.Map;

/** A single request to the Proton API. */
public record ProtonHttpRequest(
        String method,
        String path,
        String query,
        String body,
        Map<String, String> headers) {

    public ProtonHttpRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static ProtonHttpRequest get(String path, String query) {
        return new ProtonHttpRequest("GET", path, query == null ? "" : query, null, Map.of());
    }

    public static ProtonHttpRequest post(String path, String body) {
        return new ProtonHttpRequest("POST", path, "", body, Map.of());
    }

    public static ProtonHttpRequest put(String path, String body) {
        return new ProtonHttpRequest("PUT", path, "", body, Map.of());
    }

    public static ProtonHttpRequest delete(String path) {
        return new ProtonHttpRequest("DELETE", path, "", null, Map.of());
    }

    public ProtonHttpRequest withHeaders(Map<String, String> extra) {
        var merged = new java.util.LinkedHashMap<>(headers);
        merged.putAll(extra);
        return new ProtonHttpRequest(method, path, query, body, merged);
    }
}
