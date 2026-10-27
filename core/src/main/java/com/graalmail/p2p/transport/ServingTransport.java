package com.graalmail.p2p.transport;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.transport.P2PTransport.PeerConnection;

import java.util.concurrent.CompletionStage;

/**
 * A transport that also accepts inbound connections (spec section 6: mailbox and
 * client peers must serve retrieval requests, not only dial out).
 */
public interface ServingTransport extends P2PTransport {
    void setInboundHandler(InboundHandler handler);

    /** Invoked for every frame arriving on a server-side connection. */
    interface InboundHandler {
        void onMessage(PeerAddress remote, PeerConnection connection, byte[] frame);
    }
}
