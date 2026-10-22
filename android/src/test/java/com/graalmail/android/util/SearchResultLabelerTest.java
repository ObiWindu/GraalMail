/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SearchResultLabelerTest {

    @Test
    void namesTheServerForServerResults() {
        assertTrue(SearchResultLabeler.label(
                SearchResultLabeler.Origin.SERVER, "Gmail").contains("Gmail"));
        assertFalse(SearchResultLabeler.isLocalOnly(SearchResultLabeler.Origin.SERVER));
    }

    @Test
    void explainsThatOtherResultsAreLocal() {
        assertTrue(SearchResultLabeler.isLocalOnly(SearchResultLabeler.Origin.LOCAL_CACHE));
        assertTrue(SearchResultLabeler.isLocalOnly(SearchResultLabeler.Origin.SERVER_UNSUPPORTED));
        assertTrue(SearchResultLabeler.label(
                SearchResultLabeler.Origin.SERVER_UNSUPPORTED, "Yahoo").contains("Yahoo"));
    }
}
