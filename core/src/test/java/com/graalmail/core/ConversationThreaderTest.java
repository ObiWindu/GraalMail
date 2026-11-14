/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.graalmail.core.conversation.Conversation;
import com.graalmail.core.conversation.ConversationThreader;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationThreaderTest {

    @Test
    void stripsCommonPrefixes() {
        assertEquals("Hello", ConversationThreader.stripPrefixes("Re: Hello"));
        assertEquals("Hello", ConversationThreader.stripPrefixes("RE[2]: Hello"));
        assertEquals("Hello", ConversationThreader.stripPrefixes("Fwd: Re: Hello"));
        assertEquals("Hello", ConversationThreader.stripPrefixes("AW: Hello"));
    }

    @Test
    void normalizesListTagsAndCase() {
        assertEquals("hello", ConversationThreader.normalizeSubject("[dev-list] Re: Hello"));
        assertEquals(ConversationThreader.normalizeSubject("Hello"),
                ConversationThreader.normalizeSubject("re: HELLO"));
    }

    @Test
    void groupsRepliesBySubjectWithSharedParticipant() {
        Message first = message("1", "Hello", "a@example.com", Instant.parse("2026-01-01T10:00:00Z"));
        Message reply = message("2", "Re: Hello", "b@example.com", Instant.parse("2026-01-01T11:00:00Z"),
                "a@example.com");
        Message other = message("3", "Other", "c@example.com", Instant.parse("2026-01-01T12:00:00Z"));
        List<Conversation> groups = ConversationThreader.group(List.of(first, reply, other));
        assertEquals(2, groups.size());
        Conversation thread = groups.stream().filter(c -> c.size() == 2).findFirst().orElseThrow();
        assertEquals(2, thread.size());
        assertEquals("Hello", thread.subject());
        assertTrue(thread.participantEmails().contains("a@example.com"));
    }

    @Test
    void sameSubjectWithoutSharedParticipantStaysApart() {
        Message first = message("1", "Hello", "a@example.com", Instant.parse("2026-01-01T10:00:00Z"));
        Message second = message("2", "Hello", "z@example.com", Instant.parse("2026-01-01T11:00:00Z"));
        List<Conversation> groups = ConversationThreader.group(List.of(first, second));
        assertEquals(2, groups.size());
    }

    @Test
    void linksViaInReplyToHeader() {
        Message first = base("1", "Project update", "a@example.com", Instant.parse("2026-01-01T10:00:00Z"))
                .internetMessageId("<first@example.com>").build();
        Message reply = base("2", "Something else", "b@example.com", Instant.parse("2026-01-01T11:00:00Z"))
                .internetMessageId("<second@example.com>")
                .headers(List.of(new Message.Header("In-Reply-To", "<first@example.com>"))).build();
        List<Conversation> groups = ConversationThreader.group(List.of(first, reply));
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size());
    }

    private static Message message(String id, String subject, String from, Instant date) {
        return message(id, subject, from, date, null);
    }

    private static Message message(String id, String subject, String from, Instant date, String to) {
        Message.Builder builder = base(id, subject, from, date);
        if (to != null) {
            builder.to(List.of(new Address("", to)));
        }
        return builder.build();
    }

    private static Message.Builder base(String id, String subject, String from, Instant date) {
        return Message.builder(id)
                .subject(subject)
                .from(new Address("", from))
                .date(date);
    }
}
