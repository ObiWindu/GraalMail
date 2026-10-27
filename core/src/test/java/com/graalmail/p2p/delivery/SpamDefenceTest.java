package com.graalmail.p2p.delivery;

import com.graalmail.p2p.DefaultDeliveryCoordinator;
import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.NetworkId;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.RateLimiter;
import com.graalmail.p2p.SendRequest;
import com.graalmail.p2p.blockchain.DepositPolicy;
import com.graalmail.p2p.blockchain.InMemoryCursorStore;
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

class SpamDefenceTest {
    private EncryptionService crypto;
    private InMemoryIdentityService identities;
    private KeyPair senderSigning;
    private KeyPair bobEnc;
    private InMemoryPeerNetworkService peerNet;
    private InMemoryPolygonNotificationService polygon;
    private InMemoryInboxRepository inbox;

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
                bobEnc.getPublic().getEncoded(), 1, List.of(), List.of(),
                System.currentTimeMillis(), 0, false, new byte[0]));
        peerNet = new InMemoryPeerNetworkService();
        PeerAddress pa = new PeerAddress("localhost", 9500, PeerId.random());
        peerNet.registerPeer(pa, new MailboxPeer("peer1", new InMemoryContentStore(), crypto.createSigningKeyPair()));
        polygon = new InMemoryPolygonNotificationService();
        inbox = new InMemoryInboxRepository();
    }

    private DefaultDeliveryCoordinator coordinator() {
        return new DefaultDeliveryCoordinator(identities, crypto, peerNet, polygon, inbox,
                peerNet.discoverPeers().toCompletableFuture().join(), NetworkId.DEVELOPMENT,
                "0xcontract", "0xsender", StoragePolicy.defaultPolicy(), new RateLimiter(1000, 60_000), null);
    }

    private void send() throws Exception {
        coordinator().send(new SendRequest("alice@graalemail", "bob@graalemail", "hi".getBytes(),
                senderSigning, crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().get();
    }

    @Test
    void allowlistBlocksUnknownSenders() throws Exception {
        send();
        NotificationScanner scanner = new NotificationScanner(polygon, coordinator(), "dev", "0xcontract", 0);
        scanner.allowlist(new SenderAllowlist(List.of("carol@graalemail")));
        RecipientIdentity bob = new RecipientIdentity("bob@graalemail",
                bobEnc.getPublic().getEncoded(), bobEnc.getPrivate().getEncoded());
        ScanReport report = scanner.scanOnce(new InMemoryCursorStore(), bob, List.of("alice@graalemail"))
                .toCompletableFuture().get();
        assertEquals(0, report.delivered());
        assertTrue(inbox.messageIds().isEmpty());
    }

    @Test
    void allowlistPermitsListedSenders() throws Exception {
        send();
        NotificationScanner scanner = new NotificationScanner(polygon, coordinator(), "dev", "0xcontract", 0);
        scanner.allowlist(new SenderAllowlist(List.of("alice@graalemail")));
        RecipientIdentity bob = new RecipientIdentity("bob@graalemail",
                bobEnc.getPublic().getEncoded(), bobEnc.getPrivate().getEncoded());
        ScanReport report = scanner.scanOnce(new InMemoryCursorStore(), bob, List.of("alice@graalemail"))
                .toCompletableFuture().get();
        assertEquals(1, report.delivered());
    }

    @Test
    void depositRequiredRejectsProofLessSend() {
        DefaultDeliveryCoordinator coord = coordinator();
        coord.depositPolicy(DepositPolicy.required("1000", "0xrecipient"));
        try {
            coord.send(new SendRequest("alice@graalemail", "bob@graalemail", "hi".getBytes(),
                    senderSigning, crypto.createEncryptionKeyPair(), 1, 0)).toCompletableFuture().join();
            fail("expected deposit rejection");
        } catch (Exception e) {
            assertTrue(findCode(e, "PERMISSION_DENIED"));
        }
    }

    @Test
    void depositProofSatisfiesPolicy() throws Exception {
        DefaultDeliveryCoordinator coord = coordinator();
        coord.depositPolicy(DepositPolicy.required("1000", "0xrecipient"));
        coord.send(new SendRequest("alice@graalemail", "bob@graalemail", "hi".getBytes(),
                senderSigning, crypto.createEncryptionKeyPair(), 1, 0, "0xdeposit-tx")).toCompletableFuture().get();
    }

    private static boolean findCode(Throwable e, String code) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof PolyMailException p && p.code().name().equals(code)) return true;
            t = t.getCause();
        }
        return false;
    }
}
