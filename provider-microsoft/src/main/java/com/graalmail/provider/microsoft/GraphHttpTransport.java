/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.microsoft;

import java.io.IOException;

/**
 * The HTTP transport used by {@link MicrosoftGraphClient}. Injecting the
 * transport keeps the Graph client testable without network access.
 */
public interface GraphHttpTransport {

    /**
     * Executes a request.
     *
     * @param request the request to execute
     * @return the response
     * @throws IOException when the request cannot be executed
     */
    GraphHttpResponse execute(GraphHttpRequest request) throws IOException;
}
