package com.graalmail.p2p;

import com.graalmail.p2p.blockchain.InMemoryPolygonNotificationService;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.identity.IdentityRecord;
import com.graalmail.p2p.identity.InMemoryIdentityService;
import com.graalmail.p2p.mailbox.InMemoryContentStore;
import com.graalmail.p2p.mailbox.InMemoryInboxRepository;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.mailbox.QuotaTracker;
import com.graalmail.p2p.mailbox.StoragePolicy;
import com.graalmail.p2p.protocol.Envelope;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class StoragePolicyTest {
    private final EncryptionService crypto = new DefaultEncryptionService();

    private InMemoryIdentityService identities(KeyPair recipientEnc) {
        InMemoryIdentityService ids = new InMemoryIdentityService();
        ids.cache(new IdentityRecord("bob", "0xB", crypto.createSigningKeyPair().getPublic().getEncoded(),
                recipientEnc.getPublic().getEncoded(), 1, List.of(), List.of(),
                System.currentTimeMillis(), 0, false, new byte[0]));
        return ids;
    }

    private DefaultDeliveryCoordinator coordinator(InMemoryIdentityService ids, InMemoryPeerNetworkService net,
                                                  InMemoryPolygonNotificationService polygon, List<PeerAddress> peers,
                                                  StoragePolicy policy) {
        return new DefaultDeliveryCoordinator(ids, crypto, net, polygon, new InMemoryInboxRepository(),
                peers, NetworkId.DEVELOPMENT, "0xcontract", "0xsender", policy, new RateLimiter(1000, 60_000));
    }

    @Test
    void quorumNotMetWhenTooFewPeers() {
        KeyPair recipientEnc = crypto.createEncryptionKeyPair();
        KeyPair senderSigning = crypto.createSigningKeyPair();
        InMemoryPeerNetworkService net = new InMemoryPeerNetworkService();
        PeerAddress pa = new PeerAddress("localhost", 9100, PeerId.random());
        net.registerPeer(pa, new MailboxPeer("peer1", new InMemoryContentStore(), crypto.createSigningKeyPair()));
        DefaultDeliveryCoordinator coord = coordinator(identities(recipientEnc), net,
                new InMemoryPolygonNotificationService(), List.of(pa), StoragePolicy.withReplicas(2));

        CompletionException ex = assertThrows(CompletionException.class, () ->
                coord.send(new SendRequest("a@graalemail", "bob@graalemail", "hi".getBytes(),
                        senderSigning, crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException p
                && p.code() == com.graalmail.core.error.ErrorCode.STORAGE_QUORUM_NOT_MET);
    }

    @Test
    void oversizedContentRejectedBeforeStorage() {
        KeyPair recipientEnc = crypto.createEncryptionKeyPair();
        InMemoryPeerNetworkService net = new InMemoryPeerNetworkService();
        DefaultDeliveryCoordinator coord = coordinator(identities(recipientEnc), net,
                new InMemoryPolygonNotificationService(), List.of(),
                new StoragePolicy(1, 60, 16, false));

        CompletionException ex = assertThrows(CompletionException.class, () ->
                coord.send(new SendRequest("a@graalemail", "bob@graalemail", new byte[17],
                        crypto.createSigningKeyPair(), crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException);
    }

    @Test
    void quotaExceededFailsStorage() {
        MailboxPeer peer = new MailboxPeer("peer1", new InMemoryContentStore(),
                crypto.createSigningKeyPair(), new QuotaTracker(8, 8));
        Envelope env = new Envelope(ProtocolConstants.PROTOCOL_VERSION, "dev", "m1", new byte[32],
                new byte[64], new byte[0], 1, 1, System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000, new byte[0]);
        CompletionException ex = assertThrows(CompletionException.class, () ->
                peer.store(env).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException p
                && p.code() == com.graalmail.core.error.ErrorCode.STORAGE_ERROR);
    }

    @Test
    void expiredEnvelopeRejectedByPeer() {
        MailboxPeer peer = new MailboxPeer("peer1", new InMemoryContentStore(), crypto.createSigningKeyPair());
        Envelope env = new Envelope(ProtocolConstants.PROTOCOL_VERSION, "dev", "m1", new byte[32],
                new byte[8], new byte[0], 1, 1, 1000L, 2000L, new byte[0]);
        CompletionException ex = assertThrows(CompletionException.class, () ->
                peer.store(env).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException p
                && p.code() == com.graalmail.core.error.ErrorCode.MESSAGE_EXPIRED);
    }
}
