/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.util;

import java.util.UUID;

/** Identifier generation. */
public final class Ids {

    private Ids() {
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
