package com.graalmail.p2p.mail;

import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.PeerAddress;
import com.graalmail.p2p.PeerId;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.crypto.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentServiceTest {
    private EncryptionService crypto;
    private InMemoryPeerNetworkService peerNet;
    private PeerAddress peer;
    private KeyPair recipientEnc;
    private KeyPair senderSigning;
    private AttachmentService attachments;

    @BeforeEach
    void setup() {
        crypto = new DefaultEncryptionService();
        peerNet = new InMemoryPeerNetworkService();
        peer = new PeerAddress("localhost", 9300, PeerId.random());
        peerNet.registerPeer(peer, new com.graalmail.p2p.mailbox.MailboxPeer("peer1",
                new com.graalmail.p2p.mailbox.InMemoryContentStore(), crypto.createSigningKeyPair()));
        recipientEnc = crypto.createEncryptionKeyPair();
        senderSigning = crypto.createSigningKeyPair();
        attachments = new AttachmentService(crypto, peerNet);
    }

    @Test
    void uploadDownloadRoundTrip() throws Exception {
        byte[] data = "attachment bytes".getBytes();
        AttachmentPackage pkg = attachments.upload(data, "notes.txt", "text/plain",
                recipientEnc.getPublic().getEncoded(), senderSigning, List.of(peer)).toCompletableFuture().get();
        assertEquals(data.length, pkg.ref().size());
        // Public reference reveals no filename or MIME assumption beyond the stored label.
        assertFalse(pkg.ref().encryptedFilename().contains("notes"));

        byte[] out = attachments.download(pkg, recipientEnc.getPrivate().getEncoded(), List.of(peer))
                .toCompletableFuture().get();
        assertArrayEquals(data, out);
    }

    @Test
    void downloadFailsWithWrongKey() throws Exception {
        AttachmentPackage pkg = attachments.upload("secret".getBytes(), "s.bin", "application/octet-stream",
                recipientEnc.getPublic().getEncoded(), senderSigning, List.of(peer)).toCompletableFuture().get();
        KeyPair other = crypto.createEncryptionKeyPair();
        try {
            attachments.download(pkg, other.getPrivate().getEncoded(), List.of(peer)).toCompletableFuture().join();
            fail("expected decryption failure");
        } catch (Exception expected) {
        }
    }

    @Test
    void oversizedUploadRejected() {
        try {
            attachments.upload(new byte[com.graalmail.p2p.ProtocolConstants.MAX_ATTACHMENT_BYTES + 1],
                    "big.bin", "application/octet-stream", recipientEnc.getPublic().getEncoded(),
                    senderSigning, List.of(peer)).toCompletableFuture().join();
            fail("expected size rejection");
        } catch (Exception expected) {
        }
    }

    @Test
    void sealOpenRoundTrip() {
        byte[] key = crypto.randomContentKey();
        byte[] out = crypto.open(crypto.seal("sealed".getBytes(), key), key);
        assertArrayEquals("sealed".getBytes(), out);
    }
}
