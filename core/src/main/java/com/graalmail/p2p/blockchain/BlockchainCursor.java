package com.graalmail.p2p.blockchain;

public record BlockchainCursor(String chainId, String contractAddress, long lastBlock, String blockHash) {}