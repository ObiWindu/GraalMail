package com.graalmail.p2p.transport;

public record NetworkStatus(boolean connected, int peerCount, String detail) {}