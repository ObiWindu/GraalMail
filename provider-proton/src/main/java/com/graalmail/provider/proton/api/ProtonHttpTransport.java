/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.provider.proton.api;

import java.io.IOException;

/** Executes raw requests against the Proton API. Kept behind an interface for tests. */
public interface ProtonHttpTransport {

    ProtonHttpResponse execute(ProtonHttpRequest request) throws IOException;
}
