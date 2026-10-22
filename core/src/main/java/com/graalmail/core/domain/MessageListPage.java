/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.domain;

import java.util.List;

/** A page of message headers. */
public final class MessageListPage {
    private final List<Message> messages;
    private final boolean hasMore;
    private final long totalCount;

    public MessageListPage(List<Message> messages, boolean hasMore, long totalCount) {
        this.messages = List.copyOf(messages);
        this.hasMore = hasMore;
        this.totalCount = totalCount;
    }

    public static MessageListPage empty() {
        return new MessageListPage(List.of(), false, 0);
    }

    public List<Message> messages() { return messages; }
    public boolean hasMore() { return hasMore; }
    public long totalCount() { return totalCount; }
    public boolean isEmpty() { return messages.isEmpty(); }
}
