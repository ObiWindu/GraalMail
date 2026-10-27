package com.graalmail.p2p;

import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.mailbox.StorageReceipt;
import com.graalmail.p2p.protocol.Envelope;
import com.graalmail.p2p.transport.NetworkStatus;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PeerNetworkService {
    CompletionStage<Void> start();
    CompletionStage<Void> stop();
    NetworkStatus getStatus();
    CompletionStage<StorageReceipt> storeMessage(PeerAddress peer, Envelope envelope);
    CompletionStage<Envelope> fetchMessage(PeerAddress peer, String contentId);
    CompletionStage<List<PeerAddress>> discoverPeers();
    void registerPeer(PeerAddress address, MailboxPeer peer);
    /** Ed25519 public key identifying a peer, used to verify its storage receipts. Empty if unknown. */
    CompletionStage<java.util.Optional<byte[]>> peerSigningKey(PeerAddress peer);
}