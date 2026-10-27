package com.graalmail.p2p;

import java.util.ArrayList;
import java.util.List;

public record PeerAddress(String host, int port, PeerId peerId) {
    public PeerAddress {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host blank");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("invalid port");
    }
    public String endpoint() { return host + ":" + port; }

    /**
     * Parses a comma-separated {@code host:port} list (e.g. from
     * {@code polymail.mailboxPeers}) into mailbox peer addresses.
     * Blank entries are skipped; malformed entries throw
     * {@link IllegalArgumentException}. Peer ids are unknown until the
     * HELLO handshake, so they stay {@code null} here.
     */
    public static List<PeerAddress> parseList(String raw) {
        List<PeerAddress> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalArgumentException("peer must be host:port: " + trimmed);
            }
            String host = trimmed.substring(0, colon).trim();
            int port;
            try {
                port = Integer.parseInt(trimmed.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("peer port is not a number: " + trimmed);
            }
            result.add(new PeerAddress(host, port, null));
        }
        return result;
    }
}