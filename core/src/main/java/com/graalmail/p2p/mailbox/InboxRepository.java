package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.protocol.Envelope;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface InboxRepository {
    boolean exists(String messageId);
    void save(String messageId, Envelope envelope, byte[] plaintext);
    List<String> messageIds();
    byte[] plaintext(String messageId);
}