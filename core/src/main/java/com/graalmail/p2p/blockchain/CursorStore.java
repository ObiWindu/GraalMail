package com.graalmail.p2p.blockchain;

/** Durable blockchain scan cursor (spec section 13.5). Core owns the interface so the
 * delivery workflow does not depend on a concrete database; the SQLite implementation
 * lives in the storage module. */
public interface CursorStore {
    BlockchainCursor load(String chainId, String contractAddress);
    void save(BlockchainCursor cursor);
}
