package com.graalmail.p2p;

import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.identity.IdentityService;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface DeliveryCoordinator {
    CompletionStage<SendResult> send(SendRequest request);
    /**
     * Processes a notification event observed on Polygon: verifies the event, retrieves the
     * encrypted content from the P2P network, verifies integrity and sender authenticity,
     * decrypts locally and stores the message. Idempotent: repeated processing of the same
     * event must not create duplicate inbox entries.
     *
     * @param event observed notification event
     * @param senderAddress claimed sender PolyMail address, resolved and authenticated
     * @param recipientEncryptionPrivateKey PKCS#8 encoded recipient X25519 private key
     */
    CompletionStage<Void> processNotification(NotificationEvent event, String senderAddress, byte[] recipientEncryptionPrivateKey);
    List<String> inboxMessageIds();
}