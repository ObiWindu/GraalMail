/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.graalmail.core.net.RetryPolicy;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void stopsAfterMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3, 10, 2.0, 1000);
        assertTrue(policy.shouldRetry(1));
        assertTrue(policy.shouldRetry(2));
        assertFalse(policy.shouldRetry(3));
    }

    @Test
    void delayGrowsAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(5, 100, 2.0, 400);
        for (int attempt = 1; attempt <= 5; attempt++) {
            long delay = policy.delayMillis(attempt);
            assertTrue(delay >= 0 && delay <= 400, "delay out of range: " + delay);
        }
    }
}
