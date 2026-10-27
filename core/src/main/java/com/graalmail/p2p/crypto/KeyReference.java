package com.graalmail.p2p.crypto;

import java.util.UUID;

public record KeyReference(String id, String type) {
    public static KeyReference encryption() { return new KeyReference(UUID.randomUUID().toString(), "encryption"); }
    public static KeyReference signing() { return new KeyReference(UUID.randomUUID().toString(), "signing"); }
}