package com.graalmail.p2p.mailbox;

public interface ContentStore {
    void store(String contentId, byte[] bytes);
    byte[] fetch(String contentId);
    boolean contains(String contentId);
    void delete(String contentId);
}