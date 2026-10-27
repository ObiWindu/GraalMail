package com.graalmail.p2p;

public record PeerAddress(String host, int port, PeerId peerId) {
    public PeerAddress {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host blank");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("invalid port");
    }
    public String endpoint() { return host + ":" + port; }
}