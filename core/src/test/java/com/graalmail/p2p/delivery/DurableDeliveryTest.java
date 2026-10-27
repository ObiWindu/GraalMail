package com.graalmail.p2p.delivery;

import com.graalmail.p2p.DefaultDeliveryCoordinator;
import com.graalmail.p2p.DeliveryState;
import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.NetworkId;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.RateLimiter;
import com.graalmail.p2p.SendRequest;
import com.graalmail.p2p.SendResult;
import com.graalmail.p2p.blockchain.InMemoryPolygonNotificationService;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.identity.IdentityRecord;
import com.graalmail.p2p.identity.InMemoryIdentityService;
import com.graalmail.p2p.mailbox.InMemoryContentStore;
import com.graalmail.p2p.mailbox.InMemoryInboxRepository;
import com.graalmail.p2p.mailbox.MailboxPeer;
import com.graalmail.p2p.mailbox.StoragePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DurableDeliveryTest {
    private EncryptionService crypto;
    private InMemoryIdentityService identities;
    private KeyPair senderSigning;
    private InMemoryPeerNetworkService peerNet;
    private InMemoryPolygonNotificationService polygon;
    private InMemoryDeliveryStore store;

    @BeforeEach
    void setup() {
        crypto = new DefaultEncryptionService();
        identities = new InMemoryIdentityService();
        senderSigning = crypto.createSigningKeyPair();
        identities.cache(new IdentityRecord("alice", "0xA", senderSigning.getPublic().getEncoded(),
                crypto.createEncryptionKeyPair().getPublic().getEncoded(),
                1, List.of(), List.of(), System.currentTimeMillis(), 0, false, new byte[0]));
        KeyPair bobEnc = crypto.createEncryptionKeyPair();
        identities.cache(new IdentityRecord("bob", "0xB", crypto.createSigningKeyPair().getPublic().getEncoded(),
                bobEnc.getPublic().getEncoded(), 1, List.of(), List.of(),
                System.currentTimeMillis(), 0, false, new byte[0]));

        MailboxPeer peer = new MailboxPeer("peer1", new InMemoryContentStore(), crypto.createSigningKeyPair());
        peerNet = new InMemoryPeerNetworkService();
        peerNet.registerPeer(new PeerAddress("localhost", 9200, PeerId.random()), peer);
        polygon = new InMemoryPolygonNotificationService();
        store = new InMemoryDeliveryStore();
    }

    private DefaultDeliveryCoordinator coordinator(InMemoryInboxRepository inbox) {
        return new DefaultDeliveryCoordinator(identities, crypto, peerNet, polygon, inbox,
                peerNet.discoverPeers().toCompletableFuture().join(), NetworkId.DEVELOPMENT,
                "0xcontract", "0xsender", StoragePolicy.defaultPolicy(), new RateLimiter(1000, 60_000), store);
    }

    @Test
    void sendPersistsDurableState() throws Exception {
        DefaultDeliveryCoordinator coord = coordinator(new InMemoryInboxRepository());
        SendResult res = coord.send(new SendRequest("alice@graalemail", "bob@graalemail", "durable".getBytes(),
                senderSigning, crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().get();
        assertEquals(DeliveryState.NOTIFICATION_SUBMITTED, res.state());

        assertTrue(store.pendingOutgoing().stream().anyMatch(e -> e.messageId().equals(res.messageId())));
        MessageRecord rec = store.messageRecord(res.messageId());
        assertNotNull(rec);
        assertEquals("NOTIFICATION_SUBMITTED", rec.deliveryState());
        assertEquals(res.transactionHash(), rec.notificationTxHash());
    }

    @Test
    void resumeConfirmsSubmittedOperations() throws Exception {
        DefaultDeliveryCoordinator coord = coordinator(new InMemoryInboxRepository());
        SendResult res = coord.send(new SendRequest("alice@graalemail", "bob@graalemail", "resume".getBytes(),
                senderSigning, crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().get();

        // Simulate an application restart: fresh coordinator, same durable store.
        DefaultDeliveryCoordinator restarted = coordinator(new InMemoryInboxRepository());
        int confirmed = restarted.resumePending().toCompletableFuture().get();
        assertEquals(1, confirmed);
        assertTrue(store.pendingOutgoing().isEmpty());
        assertEquals("NOTIFICATION_CONFIRMED", store.messageRecord(res.messageId()).deliveryState());
    }

    @Test
    void failedSendIsRecordedDurably() {
        DefaultDeliveryCoordinator coord = new DefaultDeliveryCoordinator(identities, crypto, peerNet, polygon,
                new InMemoryInboxRepository(), List.of(), NetworkId.DEVELOPMENT,
                "0xcontract", "0xsender", StoragePolicy.defaultPolicy(), new RateLimiter(1000, 60_000), store);
        try {
            coord.send(new SendRequest("alice@graalemail", "bob@graalemail", "nope".getBytes(),
                    senderSigning, crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().join();
            fail("expected quorum failure");
        } catch (Exception expected) {
        }
        assertTrue(store.pendingOutgoing().isEmpty());
        List<MessageRecord> records = store.messageRecords();
        assertEquals(1, records.size());
        assertEquals("FAILED", records.get(0).deliveryState());
        assertEquals("STORAGE_QUORUM_NOT_MET", records.get(0).lastError());
    }
}
