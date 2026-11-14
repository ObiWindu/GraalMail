/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.graalmail.core.domain.SearchQuery;
import com.graalmail.core.search.SearchQueryParser;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchQueryParserTest {

    @Test
    void parsesOperatorsAndFreeText() {
        SearchQuery query = SearchQueryParser.parse("from:alice subject:\"quarterly report\" hello");
        assertEquals("alice", query.from());
        assertEquals("quarterly report", query.subject());
        assertEquals("hello", query.text());
    }

    @Test
    void parsesFlagsAndDates() {
        SearchQuery query = SearchQueryParser.parse("is:unread has:attachment after:2026-01-01 before:2026-02-01");
        assertTrue(query.unreadOnly());
        assertTrue(query.withAttachmentsOnly());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), query.since());
        assertEquals(Instant.parse("2026-02-01T23:59:59Z"), query.until());
    }

    @Test
    void parsesStarredAndFolderScope() {
        SearchQuery query = SearchQueryParser.parse("is:starred in:INBOX account:me@example.com");
        assertTrue(query.flaggedOnly());
        assertEquals(List.of("INBOX"), query.folderIds());
        assertEquals(List.of("me@example.com"), query.accountIds());
    }

    @Test
    void emptyInputGivesEmptyQuery() {
        assertTrue(SearchQueryParser.parse("   ").isEmpty());
        assertTrue(SearchQueryParser.parse(null).isEmpty());
    }

    @Test
    void unknownOperatorStaysAsText() {
        SearchQuery query = SearchQueryParser.parse("larger:10M hello");
        assertFalse(query.text().isBlank());
        assertTrue(query.text().contains("hello"));
    }
}
