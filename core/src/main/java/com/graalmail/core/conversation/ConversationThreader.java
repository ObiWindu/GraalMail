/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.conversation;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Groups messages into conversations.
 *
 * <p>Linking prefers {@code In-Reply-To} and {@code References} headers.
 * Messages without usable headers fall back to normalized-subject grouping
 * when they share at least one participant.</p>
 */
public final class ConversationThreader {

    private static final Pattern PREFIX = Pattern.compile(
            "^(?i)(re(\\[\\d+\\])?|fwd?|fw|aw|sv|vs|wg|antw|weitergeleitet)(\\[.*?\\])?\\s*:\\s*");

    private static final Pattern BRACKET_TAG = Pattern.compile("^\\s*\\[[^\\]]+\\]\\s*");

    private ConversationThreader() {
    }

    /**
     * Strips reply/forward prefixes for threading and display.
     *
     * @param subject the raw subject, may be {@code null}
     * @return the subject without prefixes, never {@code null}
     */
    public static String stripPrefixes(String subject) {
        String current = subject == null ? "" : subject.trim();
        for (int i = 0; i < 10 && !current.isEmpty(); i++) {
            String next = PREFIX.matcher(current).replaceFirst("");
            next = next.trim();
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    /**
     * The threading key for a subject: prefixes and list tags removed,
     * lower-cased.
     *
     * @param subject the raw subject, may be {@code null}
     * @return the normalized subject, never {@code null}
     */
    public static String normalizeSubject(String subject) {
        String current = stripPrefixes(subject);
        for (int i = 0; i < 5; i++) {
            String next = BRACKET_TAG.matcher(current).replaceFirst("").trim();
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        current = stripPrefixes(current);
        return current.toLowerCase(Locale.ROOT);
    }

    /**
     * Groups messages into conversations, newest conversation first.
     *
     * @param messages the messages to group, may be {@code null}
     * @return the conversations, newest first
     */
    public static List<Conversation> group(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> idToIndex = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            String id = cleanId(messages.get(i).internetMessageId());
            if (!id.isEmpty()) {
                idToIndex.putIfAbsent(id.toLowerCase(Locale.ROOT), i);
            }
        }
        int n = messages.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (String ref : references(messages.get(i))) {
                Integer target = idToIndex.get(ref.toLowerCase(Locale.ROOT));
                if (target != null && target != i) {
                    union(parent, i, target);
                }
            }
        }
        Map<Integer, List<Integer>> roots = new HashMap<>();
        for (int i = 0; i < n; i++) {
            roots.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        Map<Integer, List<Message>> bySubject = new HashMap<>();
        Map<Integer, Set<String>> participantsByRoot = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : roots.entrySet()) {
            Set<String> participants = new HashSet<>();
            for (int index : entry.getValue()) {
                participants.addAll(emails(messages.get(index)));
            }
            participantsByRoot.put(entry.getKey(), participants);
        }
        Map<String, List<Integer>> subjectGroups = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : roots.entrySet()) {
            String key = subjectKey(messages, entry.getValue());
            subjectGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getKey());
        }
        for (List<Integer> group : subjectGroups.values()) {
            if (group.size() < 2) {
                continue;
            }
            for (int i = 1; i < group.size(); i++) {
                if (shareParticipant(participantsByRoot.get(group.get(0)),
                        participantsByRoot.get(group.get(i)))) {
                    int a = representativeIndex(roots.get(group.get(0)));
                    int b = representativeIndex(roots.get(group.get(i)));
                    union(parent, a, b);
                }
            }
        }
        Map<Integer, List<Message>> merged = new HashMap<>();
        for (int i = 0; i < n; i++) {
            merged.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(messages.get(i));
        }
        List<Conversation> conversations = new ArrayList<>();
        for (List<Message> group : merged.values()) {
            String subject = displaySubject(group);
            String id = conversationId(group);
            conversations.add(new Conversation(id, subject, group));
        }
        conversations.sort((a, b) -> {
            if (a.latestDate() == null) {
                return 1;
            }
            if (b.latestDate() == null) {
                return -1;
            }
            return b.latestDate().compareTo(a.latestDate());
        });
        return conversations;
    }

    private static String displaySubject(List<Message> group) {
        String best = "";
        for (Message m : group) {
            String stripped = stripPrefixes(m.subject());
            if (stripped.isEmpty()) {
                continue;
            }
            if (best.isEmpty() || stripped.length() < best.length()) {
                best = stripped;
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        return group.isEmpty() ? "(no subject)" : group.get(0).subject();
    }

    private static String conversationId(List<Message> group) {
        String key = subjectKey(group);
        return Integer.toHexString(key.hashCode()) + "-" + group.size();
    }

    private static String subjectKey(List<Message> messages, List<Integer> indices) {
        List<Message> picked = new ArrayList<>();
        for (int index : indices) {
            picked.add(messages.get(index));
        }
        return subjectKey(picked);
    }

    private static String subjectKey(List<Message> group) {
        String key = "";
        for (Message m : group) {
            String normalized = normalizeSubject(m.subject());
            if (normalized.isEmpty()) {
                continue;
            }
            if (key.isEmpty() || normalized.length() < key.length()) {
                key = normalized;
            }
        }
        if (!key.isEmpty()) {
            return "s:" + key;
        }
        // Messages without subjects only thread via headers; keep them apart.
        StringBuilder sb = new StringBuilder("n:");
        for (Message m : group) {
            sb.append(m.id()).append(',');
        }
        return sb.toString();
    }

    private static boolean shareParticipant(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return true;
        }
        for (String email : a) {
            if (b.contains(email)) {
                return true;
            }
        }
        return false;
    }

    private static int representativeIndex(List<Integer> indices) {
        return indices == null || indices.isEmpty() ? 0 : indices.get(0);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    private static Set<String> emails(Message message) {
        Set<String> emails = new HashSet<>();
        addEmail(emails, message.from());
        for (Address a : message.to()) {
            addEmail(emails, a);
        }
        for (Address a : message.cc()) {
            addEmail(emails, a);
        }
        return emails;
    }

    private static void addEmail(Set<String> emails, Address address) {
        if (address != null && !address.email().isBlank()) {
            emails.add(address.email().toLowerCase(Locale.ROOT));
        }
    }

    private static List<String> references(Message message) {
        List<String> refs = new ArrayList<>();
        for (Message.Header header : message.headers()) {
            String name = header.name().toLowerCase(Locale.ROOT);
            if ("in-reply-to".equals(name) || "references".equals(name)) {
                for (String token : header.value().split("\\s+")) {
                    String cleaned = cleanId(token);
                    if (!cleaned.isEmpty()) {
                        refs.add(cleaned);
                    }
                }
            }
        }
        return refs;
    }

    private static String cleanId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("^<+", "").replaceAll(">+$", "").trim();
    }
}
