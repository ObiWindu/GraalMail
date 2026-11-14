/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.conversation;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A group of related messages (a conversation thread).
 *
 * <p>Threads are built from {@code In-Reply-To}/{@code References} headers
 * when present and fall back to normalized-subject grouping so mailing-list
 * replies without headers still land together.</p>
 */
public final class Conversation {
    private final String id;
    private final String subject;
    private final List<Message> messages;
    private final Instant latestDate;
    private final int unreadCount;
    private final boolean flagged;
    private final boolean hasAttachments;

    public Conversation(String id, String subject, List<Message> messages) {
        this.id = id == null ? "" : id;
        this.subject = subject == null ? "" : subject;
        List<Message> sorted = new ArrayList<>(messages == null ? List.of() : messages);
        sorted.sort(Comparator.comparing(Message::date, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        this.messages = Collections.unmodifiableList(sorted);
        Instant latest = null;
        int unread = 0;
        boolean star = false;
        boolean attach = false;
        for (Message m : this.messages) {
            if (m.date() != null && (latest == null || m.date().isAfter(latest))) {
                latest = m.date();
            }
            if (m.unread()) {
                unread++;
            }
            if (m.flagged()) {
                star = true;
            }
            if (m.hasAttachments()) {
                attach = true;
            }
        }
        this.latestDate = latest;
        this.unreadCount = unread;
        this.flagged = star;
        this.hasAttachments = attach;
    }

    public String id() { return id; }

    public String subject() { return subject; }

    public List<Message> messages() { return messages; }

    public int size() { return messages.size(); }

    public Instant latestDate() { return latestDate; }

    public int unreadCount() { return unreadCount; }

    public boolean flagged() { return flagged; }

    public boolean hasAttachments() { return hasAttachments; }

    /** All participant emails in the conversation, lower-cased. */
    public List<String> participantEmails() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (Message m : messages) {
            addParticipant(seen, m.from());
            for (Address a : m.to()) {
                addParticipant(seen, a);
            }
            for (Address a : m.cc()) {
                addParticipant(seen, a);
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    private static void addParticipant(Map<String, String> seen, Address a) {
        if (a == null || a.email().isBlank()) {
            return;
        }
        seen.putIfAbsent(a.email().toLowerCase(Locale.ROOT), a.email());
    }
}
