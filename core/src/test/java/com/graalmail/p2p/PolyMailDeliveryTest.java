package com.graalmail.p2p;

import com.graalmail.p2p.blockchain.BlockchainCursor;
import com.graalmail.p2p.blockchain.InMemoryPolygonNotificationService;
import com.graalmail.p2p.blockchain.NotificationEvent;
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
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PolyMailDeliveryTest {
    private EncryptionService crypto;
    private InMemoryIdentityService identityService;
    private KeyPair senderSigning;
    private KeyPair recipientSigning;
    private KeyPair recipientEnc;
    private InMemoryPeerNetworkService peerNet;
    private PeerAddress pa;
    private InMemoryPolygonNotificationService polygon;
    private InMemoryInboxRepository inbox;
    private DefaultDeliveryCoordinator senderCoord;
    private DefaultDeliveryCoordinator recipientCoord;

    @BeforeEach
    void setup() {
        crypto = new DefaultEncryptionService();
        identityService = new InMemoryIdentityService();

        recipientSigning = crypto.createSigningKeyPair();
        recipientEnc = crypto.createEncryptionKeyPair();
        identityService.cache(new IdentityRecord(
                "bob", "0xB", recipientSigning.getPublic().getEncoded(), recipientEnc.getPublic().getEncoded(),
                1, List.of(), List.of(), System.currentTimeMillis(), 0, false, new byte[0]));

        senderSigning = crypto.createSigningKeyPair();
        identityService.cache(new IdentityRecord(
                "alice", "0xA", senderSigning.getPublic().getEncoded(), crypto.createEncryptionKeyPair().getPublic().getEncoded(),
                1, List.of(), List.of(), System.currentTimeMillis(), 0, false, new byte[0]));

        InMemoryContentStore store = new InMemoryContentStore();
        MailboxPeer peer = new MailboxPeer("peer1", store, crypto.createSigningKeyPair());
        pa = new PeerAddress("localhost", 9000, PeerId.random());
        peerNet = new InMemoryPeerNetworkService();
        peerNet.registerPeer(pa, peer);

        polygon = new InMemoryPolygonNotificationService();
        inbox = new InMemoryInboxRepository();

        senderCoord = new DefaultDeliveryCoordinator(
                identityService, crypto, peerNet, polygon, new InMemoryInboxRepository(),
                List.of(pa), NetworkId.DEVELOPMENT, "0xcontract", "0xsender", 1);
        recipientCoord = new DefaultDeliveryCoordinator(
                identityService, crypto, peerNet, polygon, inbox,
                List.of(pa), NetworkId.DEVELOPMENT, "0xcontract", "0xsender", 1);
    }

    private NotificationEvent sendAndScan(byte[] content) throws Exception {
        SendRequest req = new SendRequest("alice@graalemail", "bob@graalemail", content,
                senderSigning, crypto.createEncryptionKeyPair(), 1, 0);
        SendResult res = senderCoord.send(req).toCompletableFuture().get();
        assertNotNull(res.transactionHash());
        assertEquals(DeliveryState.NOTIFICATION_SUBMITTED, res.state());
        var events = polygon.scan(new BlockchainCursor("dev", "0xcontract", 0, ""), new BlockchainCursor("dev", "0xcontract", 100, "")).toCompletableFuture().get();
        assertFalse(events.isEmpty());
        return events.stream().filter(e -> e.messageId().equals(res.messageId())).findFirst().orElseThrow();
    }

    @Test
    void sendAndReceive() throws Exception {
        NotificationEvent ev = sendAndScan("hello bob".getBytes());
        // Recipient recognises the event via its notification tag (spec 5.2).
        assertTrue(NotificationTag.matches(recipientEnc.getPublic().getEncoded(), ev.messageId(), ev.inboxTag()));
        recipientCoord.processNotification(ev, "alice@graalemail", recipientEnc.getPrivate().getEncoded()).toCompletableFuture().get();
        assertTrue(inbox.exists(ev.messageId()));
        assertArrayEquals("hello bob".getBytes(), inbox.plaintext(ev.messageId()));
    }

    @Test
    void duplicateNotificationDoesNotDuplicateInbox() throws Exception {
        NotificationEvent ev = sendAndScan("hello again".getBytes());
        recipientCoord.processNotification(ev, "alice@graalemail", recipientEnc.getPrivate().getEncoded()).toCompletableFuture().get();
        recipientCoord.processNotification(ev, "alice@graalemail", recipientEnc.getPrivate().getEncoded()).toCompletableFuture().get();
        assertEquals(1, inbox.messageIds().size());
    }

    @Test
    void invalidSenderSignatureRejected() throws Exception {
        NotificationEvent ev = sendAndScan("forged?".getBytes());
        // Unknown sender: resolution fails, nothing is displayed as authenticated mail.
        CompletionException ex = assertThrows(CompletionException.class, () ->
                recipientCoord.processNotification(ev, "mallory@graalemail", recipientEnc.getPrivate().getEncoded()).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException);
        assertFalse(inbox.exists(ev.messageId()));
    }

    @Test
    void tamperedContentHashRejected() throws Exception {
        NotificationEvent ev = sendAndScan("tamper me".getBytes());
        byte[] bad = ev.contentHash().clone();
        bad[0] ^= 1;
        NotificationEvent tampered = new NotificationEvent(ev.chainId(), ev.contractAddress(), ev.transactionHash(),
                ev.logIndex(), ev.blockNumber(), ev.blockHash(), ev.messageId(), bad, ev.inboxTag(), ev.expiresAt(), ev.observedAt());
        CompletionException ex = assertThrows(CompletionException.class, () ->
                recipientCoord.processNotification(tampered, "alice@graalemail", recipientEnc.getPrivate().getEncoded()).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException);
        assertFalse(inbox.exists(ev.messageId()));
    }

    @Test
    void revokedSenderKeyRejected() throws Exception {
        NotificationEvent ev = sendAndScan("late revocation".getBytes());
        // Sender rotates/revokes after sending: retrieval must fail closed.
        identityService.cache(new IdentityRecord(
                "alice", "0xA", senderSigning.getPublic().getEncoded(), crypto.createEncryptionKeyPair().getPublic().getEncoded(),
                2, List.of(), List.of(), System.currentTimeMillis(), 0, true, new byte[0]));
        CompletionException ex = assertThrows(CompletionException.class, () ->
                recipientCoord.processNotification(ev, "alice@graalemail", recipientEnc.getPrivate().getEncoded()).toCompletableFuture().join());
        assertTrue(ex.getCause() instanceof PolyMailException);
        assertFalse(inbox.exists(ev.messageId()));
    }
}
