package com.graalmail.p2p.delivery;

import com.graalmail.p2p.DefaultDeliveryCoordinator;
import com.graalmail.p2p.DeliveryState;
import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.NetworkId;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.SendRequest;
import com.graalmail.p2p.SendResult;
import com.graalmail.p2p.blockchain.BlockchainCursor;
import com.graalmail.p2p.blockchain.InMemoryCursorStore;
import com.graalmail.p2p.blockchain.InMemoryPolygonNotificationService;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.identity.IdentityRecord;
import com.graalmail.p2p.identity.InMemoryIdentityService;
import com.graalmail.p2p.mailbox.InMemoryContentStore;
import com.graalmail.p2p.mailbox.InMemoryInboxRepository;
import com.graalmail.p2p.mailbox.MailboxPeer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotificationScannerTest {
    private EncryptionService crypto;
    private InMemoryIdentityService identities;
    private KeyPair senderSigning;
    private KeyPair bobEnc;
    private InMemoryPeerNetworkService peerNet;
    private InMemoryPolygonNotificationService polygon;
    private InMemoryInboxRepository inbox;
    private DefaultDeliveryCoordinator senderCoord;
    private DefaultDeliveryCoordinator recipientCoord;
    private NotificationScanner scanner;

    @BeforeEach
    void setup() {
        crypto = new DefaultEncryptionService();
        identities = new InMemoryIdentityService();

        senderSigning = crypto.createSigningKeyPair();
        identities.cache(new IdentityRecord("alice", "0xA", senderSigning.getPublic().getEncoded(),
                crypto.createEncryptionKeyPair().getPublic().getEncoded(),
                1, List.of(), List.of(), System.currentTimeMillis(), 0, false, new byte[0]));

        bobEnc = crypto.createEncryptionKeyPair();
        identities.cache(new IdentityRecord("bob", "0xB", crypto.createSigningKeyPair().getPublic().getEncoded(),
                bobEnc.getPublic().getEncoded(),
                1, List.of(), List.of(), System.currentTimeMillis(), 0, false, new byte[0]));

        KeyPair carolEnc = crypto.createEncryptionKeyPair();
        identities.cache(new IdentityRecord("carol", "0xC", crypto.createSigningKeyPair().getPublic().getEncoded(),
                carolEnc.getPublic().getEncoded(),
                1, List.of(), List.of(), System.currentTimeMillis(), 0, false, new byte[0]));

        MailboxPeer peer = new MailboxPeer("peer1", new InMemoryContentStore(), crypto.createSigningKeyPair());
        PeerAddress pa = new PeerAddress("localhost", 9001, PeerId.random());
        peerNet = new InMemoryPeerNetworkService();
        peerNet.registerPeer(pa, peer);
        polygon = new InMemoryPolygonNotificationService();
        inbox = new InMemoryInboxRepository();

        senderCoord = new DefaultDeliveryCoordinator(identities, crypto, peerNet, polygon,
                new InMemoryInboxRepository(), List.of(pa), NetworkId.DEVELOPMENT, "0xcontract", "0xsender", 1);
        recipientCoord = new DefaultDeliveryCoordinator(identities, crypto, peerNet, polygon, inbox,
                List.of(pa), NetworkId.DEVELOPMENT, "0xcontract", "0xsender", 1);
        scanner = new NotificationScanner(polygon, recipientCoord, "dev", "0xcontract", 2);
    }

    private void send(String from, String to, String body) throws Exception {
        senderCoord.send(new SendRequest(from, to, body.getBytes(), senderSigning,
                crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().get();
    }

    private RecipientIdentity bob() {
        return new RecipientIdentity("bob@graalemail", bobEnc.getPublic().getEncoded(), bobEnc.getPrivate().getEncoded());
    }

    @Test
    void offlineRecipientRecoversMissedEvents() throws Exception {
        send("alice@graalemail", "bob@graalemail", "missed me");
        // Recipient was terminated: fresh scanner with empty durable cursor recovers.
        InMemoryCursorStore cursors = new InMemoryCursorStore();
        ScanReport report = scanner.scanOnce(cursors, bob(), List.of("alice@graalemail")).toCompletableFuture().get();
        assertEquals(1, report.delivered());
        assertEquals(0, report.failed());
        assertEquals(1, inbox.messageIds().size());
        assertTrue(cursors.load("dev", "0xcontract").lastBlock() > 0);
    }

    @Test
    void secondScanDoesNotDuplicate() throws Exception {
        send("alice@graalemail", "bob@graalemail", "once");
        InMemoryCursorStore cursors = new InMemoryCursorStore();
        scanner.scanOnce(cursors, bob(), List.of("alice@graalemail")).toCompletableFuture().get();
        ScanReport second = scanner.scanOnce(cursors, bob(), List.of("alice@graalemail")).toCompletableFuture().get();
        assertEquals(0, second.delivered());
        assertEquals(1, inbox.messageIds().size());
    }

    @Test
    void eventsForOthersAreSkipped() throws Exception {
        send("alice@graalemail", "carol@graalemail", "not for bob");
        InMemoryCursorStore cursors = new InMemoryCursorStore();
        ScanReport report = scanner.scanOnce(cursors, bob(), List.of("alice@graalemail")).toCompletableFuture().get();
        assertEquals(0, report.delivered());
        assertEquals(1, report.skipped());
        assertTrue(inbox.messageIds().isEmpty());
    }

    @Test
    void reorgDoesNotDuplicateOrLoseCursor() throws Exception {
        send("alice@graalemail", "bob@graalemail", "reorg me");
        InMemoryCursorStore cursors = new InMemoryCursorStore();
        scanner.scanOnce(cursors, bob(), List.of("alice@graalemail")).toCompletableFuture().get();
        long advanced = cursors.load("dev", "0xcontract").lastBlock();
        assertTrue(advanced > 0);

        // Chain reorganization orphans the notification block.
        polygon.dropAbove(0);
        ScanReport report = scanner.scanOnce(cursors, bob(), List.of("alice@graalemail")).toCompletableFuture().get();
        assertEquals(0, report.delivered());
        // Already-delivered mail stays delivered exactly once; cursor tracks the new head.
        assertEquals(1, inbox.messageIds().size());
        assertEquals(polygon.headBlock().toCompletableFuture().get(),
                cursors.load("dev", "0xcontract").lastBlock());
    }
}
