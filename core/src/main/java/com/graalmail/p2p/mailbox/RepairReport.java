package com.graalmail.p2p.mailbox;

import java.util.List;

/** Outcome of a replication repair pass. */
public record RepairReport(
        String contentId,
        int healthyBefore,
        int healthyAfter,
        int required,
        List<String> repairedPeers,
        List<String> failedPeers
) {
    public boolean quorumMet() { return healthyAfter >= required; }
}
