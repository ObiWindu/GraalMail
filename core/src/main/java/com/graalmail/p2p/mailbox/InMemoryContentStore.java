package com.graalmail.p2p.mailbox;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class InMemoryContentStore implements ContentStore {
    private final Map<String, byte[]> data = new ConcurrentHashMap<>();

    @Override
    public void store(String contentId, byte[] bytes) { data.put(contentId, bytes == null ? null : bytes.clone()); }
    @Override
    public byte[] fetch(String contentId) { byte[] v = data.get(contentId); return v == null ? null : v.clone(); }
    @Override
    public boolean contains(String contentId) { return data.containsKey(contentId); }
    @Override
    public void delete(String contentId) { data.remove(contentId); }
}