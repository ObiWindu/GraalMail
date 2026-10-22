/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.sync;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import com.graalmail.core.util.Log;

/** Desktop scheduler that runs the sync engine on a fixed interval. */
public final class PeriodicSyncScheduler implements SyncScheduler {

    private static final Logger LOG = Log.of(PeriodicSyncScheduler.class);

    private final SyncEngine engine;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "graalmail-sync");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PeriodicSyncScheduler(SyncEngine engine) {
        this.engine = engine;
    }

    @Override
    public void start(Duration interval) {
        long millis = Math.max(30_000L, interval.toMillis());
        running.set(true);
        executor.scheduleWithFixedDelay(() -> {
            try {
                engine.syncAll();
            } catch (RuntimeException e) {
                LOG.warn("Scheduled synchronization failed: {}", e.getMessage());
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running.set(false);
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
