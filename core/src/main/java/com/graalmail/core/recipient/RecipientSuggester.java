/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.recipient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks recipient candidates for the auto completion of the compose window.
 *
 * <p>The suggester is pure: the caller supplies the candidates, the suggester
 * only filters and ranks them. Duplicate addresses are merged and the best
 * source of an address wins.</p>
 */
public final class RecipientSuggester {

    private RecipientSuggester() {
    }

    /**
     * Ranks the candidates against a search term.
     *
     * @param query the search term, may be {@code null} or empty
     * @param candidates the candidates, may contain duplicates
     * @param limit the maximum number of suggestions
     * @return the ranked suggestions, best match first
     */
    public static List<RecipientSuggestion> suggest(String query, List<RecipientSuggestion> candidates,
                                                    int limit) {
        if (limit <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, RecipientSuggestion> merged = new LinkedHashMap<>();
        for (RecipientSuggestion candidate : candidates) {
            if (candidate == null || candidate.email().isEmpty()) {
                continue;
            }
            String key = candidate.email().toLowerCase(java.util.Locale.ROOT);
            RecipientSuggestion known = merged.get(key);
            if (known == null || candidate.baseScore() > known.baseScore()) {
                merged.put(key, candidate);
            }
        }

        List<RecipientSuggestion> scored = new ArrayList<>(merged.size());
        for (RecipientSuggestion candidate : merged.values()) {
            int score = candidate.scoreFor(query);
            if (score == Integer.MIN_VALUE) {
                continue;
            }
            scored.add(new RecipientSuggestion(candidate.name(), candidate.email(), candidate.source(), score));
        }
        scored.sort(Comparator.comparingInt(RecipientSuggestion::score).reversed()
                .thenComparing(RecipientSuggestion::displayLabel, String.CASE_INSENSITIVE_ORDER));
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }
}
