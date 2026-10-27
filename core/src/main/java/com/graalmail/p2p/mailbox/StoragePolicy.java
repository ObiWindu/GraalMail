package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.ProtocolConstants;

/**
 * Sender-chosen storage policy for one message (spec section 9.1). A policy never
 * implies a guarantee that every replica stays available; it only defines how many
 * independently verified acknowledgements are required before notifying.
 */
public record StoragePolicy(
        int requiredReplicas,
        long retentionSeconds,
        long maxContentBytes,
        boolean bestEffortDelivery
) {
    public StoragePolicy {
        if (requiredReplicas < 1) throw new IllegalArgumentException("requiredReplicas >= 1");
        if (retentionSeconds <= 0) throw new IllegalArgumentException("retentionSeconds > 0");
        if (maxContentBytes <= 0) throw new IllegalArgumentException("maxContentBytes > 0");
    }

    public static StoragePolicy defaultPolicy() {
        return new StoragePolicy(1, ProtocolConstants.DEFAULT_RETENTION_SECONDS,
                ProtocolConstants.MAX_MESSAGE_BYTES, false);
    }

    public static StoragePolicy withReplicas(int replicas) {
        return new StoragePolicy(replicas, ProtocolConstants.DEFAULT_RETENTION_SECONDS,
                ProtocolConstants.MAX_MESSAGE_BYTES, false);
    }
}
