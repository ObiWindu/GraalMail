package com.graalmail.p2p.transport;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.transport.NetworkStatus;

import java.util.concurrent.CompletionStage;

public interface P2PTransport extends AutoCloseable {
    CompletionStage<PeerConnection> connect(PeerAddress address);
    CompletionStage<Void> start();
    CompletionStage<Void> stop();
    NetworkStatus getStatus();

    interface PeerConnection extends AutoCloseable {
        CompletionStage<Void> send(byte[] data);
        CompletionStage<byte[]> receive();
    }
}