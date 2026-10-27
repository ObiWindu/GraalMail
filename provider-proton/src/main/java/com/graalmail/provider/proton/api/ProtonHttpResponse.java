/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

/** A response from the Proton API. */
public record ProtonHttpResponse(int status, String body) {

    public ProtonHttpResponse {
        body = body == null ? "" : body;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }
}
