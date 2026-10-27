package com.graalmail.p2p.discovery;

import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.transport.NetworkStatus;

import java.util.List;

public record PeerRecord(
        PeerId peerId,
        String networkId,
        String protocolVersion,
        List<String> transports,
        List<String> addresses,
        boolean mailboxCapable,
        long createdAt,
        long expiresAt,
        byte[] signature
) {}