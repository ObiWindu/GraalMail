package com.graalmail.p2p.mailbox;

import com.graalmail.p2p.protocol.Envelope;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemoryInboxRepository implements InboxRepository {
    private final Map<String, Envelope> envelopes = new ConcurrentHashMap<>();
    private final Map<String, byte[]> plaintexts = new ConcurrentHashMap<>();

    @Override
    public boolean exists(String messageId) { return envelopes.containsKey(messageId); }

    @Override
    public void save(String messageId, Envelope envelope, byte[] plaintext) {
        envelopes.putIfAbsent(messageId, envelope);
        plaintexts.putIfAbsent(messageId, plaintext);
    }

    @Override
    public List<String> messageIds() { return new ArrayList<>(envelopes.keySet()); }

    @Override
    public byte[] plaintext(String messageId) { return plaintexts.get(messageId); }
}
