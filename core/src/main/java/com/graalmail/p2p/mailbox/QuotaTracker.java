package com.graalmail.p2p.mailbox;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * Tracks stored bytes per peer against configured quotas (spec section 9.1: maximum
 * content size, storage quotas). Thread-safe; purely advisory for the in-memory
 * network, enforced by mailbox peers before accepting content.
 */
public class QuotaTracker {
    private final long perPeerByteLimit;
    private final long totalByteLimit;
    private final Map<String, AtomicLong> usedByPeer = new ConcurrentHashMap<>();
    private final AtomicLong totalUsed = new AtomicLong();

    public QuotaTracker(long perPeerByteLimit, long totalByteLimit) {
        this.perPeerByteLimit = perPeerByteLimit;
        this.totalByteLimit = totalByteLimit;
    }

    public static QuotaTracker unlimited() {
        return new QuotaTracker(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    /** Reserves {@code bytes} for {@code peerId}; returns false without reserving if over quota. */
    public synchronized boolean tryAcquire(String peerId, long bytes) {
        if (bytes < 0) return false;
        long peerUsed = usedByPeer.computeIfAbsent(peerId, k -> new AtomicLong()).get();
        if (peerUsed + bytes > perPeerByteLimit) return false;
        if (totalUsed.get() + bytes > totalByteLimit) return false;
        usedByPeer.get(peerId).addAndGet(bytes);
        totalUsed.addAndGet(bytes);
        return true;
    }

    public synchronized void release(String peerId, long bytes) {
        AtomicLong peerUsed = usedByPeer.get(peerId);
        if (peerUsed != null) peerUsed.addAndGet(-Math.min(bytes, peerUsed.get()));
        totalUsed.addAndGet(-Math.min(bytes, totalUsed.get()));
    }

    public long usedBy(String peerId) {
        AtomicLong v = usedByPeer.get(peerId);
        return v == null ? 0 : v.get();
    }

    public long totalUsed() { return totalUsed.get(); }
}
