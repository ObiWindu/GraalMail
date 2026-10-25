/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin logging facade that redacts sensitive values before delegating to SLF4J.
 */
public final class Log {

    private Log() {
    }

    public static Logger of(Class<?> type) {
        return LoggerFactory.getLogger(type);
    }

    public static Logger of(String name) {
        return LoggerFactory.getLogger(name);
    }
}
