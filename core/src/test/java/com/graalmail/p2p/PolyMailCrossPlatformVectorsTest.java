package com.graalmail.p2p;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.graalmail.p2p.identity.PolyMailAddress;
import com.graalmail.p2p.protocol.Envelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-platform protocol vectors (spec 19.4): desktop and Android share
 * {@code :core}, so these golden values pin the wire format both platforms
 * must produce. Any change to canonical CBOR, hashing, address normalization,
 * or peer parsing breaks this test loudly by design.
 */
class PolyMailCrossPlatformVectorsTest {

    private static final String GOLDEN_CBOR_SHA256 =
            "526cbceedd3b17b962ed0588f5fd29f865d7ddae203badb139fd910c1bf1fbc4";
    private static final int GOLDEN_CBOR_LEN = 213;

    private static Envelope vectorEnvelope() {
        return new Envelope(
                "GPMP/1", "test", "msg-vector-001",
                new byte[]{1, 2, 3, 4},
                "hello-polymail".getBytes(StandardCharsets.UTF_8),
                new byte[]{9, 9},
                3, 7, 1700000000L, 1700604800L,
                new byte[]{5, 6, 7, 8});
    }

    @Test
    void envelopeCborMatchesGoldenHash() throws Exception {
        byte[] cbor = vectorEnvelope().toCbor();
        assertEquals(GOLDEN_CBOR_LEN, cbor.length);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(cbor);
        assertEquals(GOLDEN_CBOR_SHA256, HexFormat.of().formatHex(hash));
    }

    @Test
    void envelopeCborIsDeterministicAndRoundTrips() {
        Envelope envelope = vectorEnvelope();
        assertArrayEquals(envelope.toCbor(), envelope.toCbor());
        assertEquals(envelope, Envelope.fromCbor(envelope.toCbor()));
    }

    @Test
    void addressNormalizationVectors() {
        assertEquals("alice@graalemail", PolyMailAddress.parse("Alice@GraalEmail").canonical());
        assertEquals("bob@graalemail", PolyMailAddress.parse("  BOB  ").canonical());
        assertEquals("carol@polymail", PolyMailAddress.parse("Carol@PolyMail").canonical());
        assertEquals("dave@graalemail", PolyMailAddress.parse("dave").canonical());
    }

    @Test
    void peerAddressParseVectors() {
        List<PeerAddress> peers = PeerAddress.parseList(
                "mailbox1.example.com:9000, mailbox2.example.com:9001");
        assertEquals(2, peers.size());
        assertEquals("mailbox1.example.com:9000", peers.get(0).endpoint());
        assertEquals(9001, peers.get(1).port());
        assertEquals(0, PeerAddress.parseList("").size());
        assertEquals(0, PeerAddress.parseList(null).size());
        assertThrows(IllegalArgumentException.class,
                () -> PeerAddress.parseList("not-a-peer"));
        assertThrows(IllegalArgumentException.class,
                () -> PeerAddress.parseList("host:notaport"));
    }
}
