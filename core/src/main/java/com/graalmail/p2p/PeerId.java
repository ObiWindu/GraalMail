package com.graalmail.p2p;

import java.util.UUID;

public record PeerId(String value) {
    public PeerId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("peer id blank");
    }
    public static PeerId random() { return new PeerId(UUID.randomUUID().toString()); }
    @Override public String toString() { return value; }
}