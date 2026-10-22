/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.net;

import java.util.concurrent.ThreadLocalRandom;

/** Bounded exponential backoff with jitter. */
public final class RetryPolicy {

    private final int maxAttempts;
    private final long initialDelayMillis;
    private final double multiplier;
    private final long maxDelayMillis;

    public RetryPolicy(int maxAttempts, long initialDelayMillis, double multiplier, long maxDelayMillis) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMillis = initialDelayMillis;
        this.multiplier = multiplier;
        this.maxDelayMillis = maxDelayMillis;
    }

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(5, 1_000, 2.0, 60_000);
    }

    public int maxAttempts() { return maxAttempts; }

    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }

    /** Delay before the given (1-based) attempt, with full jitter. */
    public long delayMillis(int attempt) {
        if (attempt <= 0) {
            return 0;
        }
        double exponential = initialDelayMillis * Math.pow(multiplier, attempt - 1);
        long capped = (long) Math.min(exponential, (double) maxDelayMillis);
        if (capped <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextLong(capped / 2 + 1, capped + 1);
    }
}
