package com.graalmail.p2p.blockchain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Non-durable cursor store for tests and ephemeral nodes. */
public class InMemoryCursorStore implements CursorStore {
    private final Map<String, BlockchainCursor> cursors = new ConcurrentHashMap<>();

    @Override
    public BlockchainCursor load(String chainId, String contractAddress) {
        return cursors.getOrDefault(chainId + "|" + contractAddress,
                new BlockchainCursor(chainId, contractAddress, 0, ""));
    }

    @Override
    public void save(BlockchainCursor cursor) {
        cursors.put(cursor.chainId() + "|" + cursor.contractAddress(), cursor);
    }
}
