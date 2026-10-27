package com.graalmail.p2p;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Token-bucket rate limiter for notification publishing and retrieval (spec sections
 * 5.5 and 18: anti-spam, rate limits, denial-of-service resistance). First version
 * enforces off-chain per-key limits; on-chain deposits are a later option.
 */
public class RateLimiter {
    private final int maxTokens;
    private final long refillIntervalMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        int tokens;
        long lastRefill;
        Bucket(int tokens, long now) { this.tokens = tokens; this.lastRefill = now; }
    }

    public RateLimiter(int maxTokens, long refillIntervalMillis) {
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens >= 1");
        if (refillIntervalMillis <= 0) throw new IllegalArgumentException("refillIntervalMillis > 0");
        this.maxTokens = maxTokens;
        this.refillIntervalMillis = refillIntervalMillis;
    }

    /** Returns true and consumes one token, or false if the key is currently limited. */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, System.currentTimeMillis());
    }

    synchronized boolean tryAcquire(String key, long now) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(maxTokens, now));
        long elapsed = now - b.lastRefill;
        if (elapsed >= refillIntervalMillis) {
            long refills = elapsed / refillIntervalMillis;
            b.tokens = (int) Math.min(maxTokens, b.tokens + refills);
            b.lastRefill += refills * refillIntervalMillis;
        }
        if (b.tokens <= 0) return false;
        b.tokens--;
        return true;
    }
}
