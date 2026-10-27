package com.graalmail.p2p.delivery;

import com.graalmail.p2p.blockchain.BlockchainCursor;
import java.util.List;

/** Outcome of one scanner pass over Polygon logs. */
public record ScanReport(
        int scanned,
        int matched,
        int delivered,
        int skipped,
        int failed,
        List<String> failures,
        BlockchainCursor newCursor
) {}
