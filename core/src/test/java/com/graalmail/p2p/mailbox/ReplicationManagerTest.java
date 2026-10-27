package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import com.graalmail.p2p.protocol.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationManagerTest {
    private EncryptionService crypto;
    private InMemoryPeerNetworkService peerNet;
    private PeerAddress peer1;
    private PeerAddress peer2;
    private MailboxPeer mailbox1;
    private ReplicationManager replication;

    @BeforeEach
    void setup() {
        crypto = new DefaultEncryptionService();
        peerNet = new InMemoryPeerNetworkService();
        mailbox1 = new MailboxPeer("peer1", new InMemoryContentStore(), crypto.createSigningKeyPair());
        MailboxPeer mailbox2 = new MailboxPeer("peer2", new InMemoryContentStore(), crypto.createSigningKeyPair());
        peer1 = new PeerAddress("localhost", 9401, PeerId.random());
        peer2 = new PeerAddress("localhost", 9402, PeerId.random());
        peerNet.registerPeer(peer1, mailbox1);
        peerNet.registerPeer(peer2, mailbox2);
        replication = new ReplicationManager(peerNet, crypto);
    }

    private Envelope envelope(String id) {
        long now = System.currentTimeMillis();
        return new Envelope("GPMP/1", "dev", id, new byte[32], new byte[16], new byte[0],
                1, 1, now, now + 60_000, new byte[64]);
    }

    @Test
    void topsUpMissingReplica() throws Exception {
        Envelope env = envelope("m1");
        peerNet.storeMessage(peer1, env).toCompletableFuture().get();

        RepairReport report = replication.ensureReplicas(env, StoragePolicy.withReplicas(2))
                .toCompletableFuture().get();
        assertTrue(report.quorumMet());
        assertEquals(1, report.healthyBefore());
        assertEquals(2, report.healthyAfter());
        assertNotNull(peerNet.fetchMessage(peer2, ContentIds.hex(env.contentHash())).toCompletableFuture().get());
    }

    @Test
    void survivesLossOfSingleReplica() throws Exception {
        Envelope env = envelope("m2");
        peerNet.storeMessage(peer1, env).toCompletableFuture().get();
        peerNet.storeMessage(peer2, env).toCompletableFuture().get();

        // Peer 1 loses its disk.
        mailbox1.store().delete(ContentIds.hex(env.contentHash()));

        RepairReport report = replication.ensureReplicas(env, StoragePolicy.withReplicas(2))
                .toCompletableFuture().get();
        assertTrue(report.quorumMet());
        assertEquals(2, report.healthyAfter());
    }

    @Test
    void alreadyHealthyNeedsNoRepair() throws Exception {
        Envelope env = envelope("m3");
        peerNet.storeMessage(peer1, env).toCompletableFuture().get();
        peerNet.storeMessage(peer2, env).toCompletableFuture().get();

        RepairReport report = replication.ensureReplicas(env, StoragePolicy.withReplicas(2))
                .toCompletableFuture().get();
        assertTrue(report.quorumMet());
        assertTrue(report.repairedPeers().isEmpty());
    }
}
