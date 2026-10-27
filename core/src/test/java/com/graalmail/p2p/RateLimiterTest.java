package com.graalmail.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    @Test
    void allowsUpToBurstThenLimits() {
        RateLimiter limiter = new RateLimiter(2, 60_000);
        assertTrue(limiter.tryAcquire("k", 0));
        assertTrue(limiter.tryAcquire("k", 0));
        assertFalse(limiter.tryAcquire("k", 0));
    }

    @Test
    void refillsAfterInterval() {
        RateLimiter limiter = new RateLimiter(1, 1_000);
        assertTrue(limiter.tryAcquire("k", 0));
        assertFalse(limiter.tryAcquire("k", 500));
        assertTrue(limiter.tryAcquire("k", 1_000));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, 60_000);
        assertTrue(limiter.tryAcquire("a", 0));
        assertTrue(limiter.tryAcquire("b", 0));
        assertFalse(limiter.tryAcquire("a", 0));
    }
}
