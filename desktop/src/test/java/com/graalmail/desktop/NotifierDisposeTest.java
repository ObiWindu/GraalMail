/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tray icon must be removable without a notification area, and removal
 * must be idempotent: the application calls it on every shutdown path.
 */
class NotifierDisposeTest {

    @Test
    void disposeWithoutTrayDoesNotThrow() {
        Notifier notifier = new Notifier();
        assertDoesNotThrow(notifier::dispose);
        assertDoesNotThrow(notifier::dispose);
    }
}
