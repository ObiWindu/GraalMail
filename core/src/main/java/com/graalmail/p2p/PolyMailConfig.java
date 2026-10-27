package com.graalmail.p2p;

import java.util.List;

/**
 * Deployment configuration for one PolyMail node (spec sections 6.3, 11, 15, 16).
 * Carries no secrets: keys stay in the platform {@code SecureKeyStore}.
 */
public record PolyMailConfig(
        List<String> rpcUrls,
        String contractAddress,
        String chainId,
        NetworkId networkId,
        int listenPort,
        int confirmationDepth,
        int requiredReplicas
) {
    public PolyMailConfig {
        if (rpcUrls == null || rpcUrls.isEmpty()) throw new IllegalArgumentException("rpcUrls required");
        if (contractAddress == null || contractAddress.isBlank()) throw new IllegalArgumentException("contractAddress required");
        if (chainId == null || chainId.isBlank()) throw new IllegalArgumentException("chainId required");
        if (networkId == null) throw new IllegalArgumentException("networkId required");
        rpcUrls = List.copyOf(rpcUrls);
    }

    public static PolyMailConfig development(String rpcUrl, String contractAddress) {
        return new PolyMailConfig(List.of(rpcUrl), contractAddress, "dev", NetworkId.DEVELOPMENT, 0, 2, 1);
    }
}
