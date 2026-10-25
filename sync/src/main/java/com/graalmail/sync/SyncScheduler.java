/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import java.time.Duration;

/** Platform neutral scheduling of background synchronization. */
public interface SyncScheduler {

    void start(Duration interval);

    void stop();

    boolean isRunning();
}
