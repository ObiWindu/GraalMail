/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.recipient;

import java.util.Locale;
import java.util.Objects;

/**
 * A candidate for the recipient auto completion of the compose window.
 *
 * <p>A suggestion carries the address, an optional display name and the
 * origin of the entry. The origin decides how strongly an entry is ranked
 * when the same address is known from several places.</p>
 */
public final class RecipientSuggestion {

    /** Where a suggestion comes from. */
    public enum Source {
        /** A contact of the local address book. */
        CONTACT,
        /** An address that was used recently in a conversation. */
        RECENT,
        /** The address of a configured account. */
        ACCOUNT
    }

    private final String name;
    private final String email;
    private final Source source;
    private final int score;

    public RecipientSuggestion(String name, String email, Source source, int score) {
        this.name = name == null ? "" : name.trim();
        this.email = email == null ? "" : email.trim();
        this.source = source == null ? Source.CONTACT : source;
        this.score = score;
    }

    public static RecipientSuggestion contact(String name, String email) {
        return new RecipientSuggestion(name, email, Source.CONTACT, 0);
    }

    public static RecipientSuggestion recent(String name, String email) {
        return new RecipientSuggestion(name, email, Source.RECENT, 0);
    }

    public static RecipientSuggestion account(String name, String email) {
        return new RecipientSuggestion(name, email, Source.ACCOUNT, 0);
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public Source source() {
        return source;
    }

    /** The ranking score; a higher value is a better match. */
    public int score() {
        return score;
    }

    /** The label that is shown in the suggestion list. */
    public String displayLabel() {
        if (name.isEmpty()) {
            return email;
        }
        if (email.isEmpty()) {
            return name;
        }
        return name + " <" + email + ">";
    }

    /** The RFC 5322 form of the suggestion. */
    public String format() {
        if (email.isEmpty()) {
            return name;
        }
        if (name.isEmpty()) {
            return email;
        }
        return name + " <" + email + ">";
    }

    /**
     * Scores the suggestion against a search term.
     *
     * @param query the search term, may be empty
     * @return the score of this suggestion
     */
    public int scoreFor(String query) {
        if (query == null || query.isBlank()) {
            return baseScore();
        }
        String term = query.trim().toLowerCase(Locale.ROOT);
        String mail = email.toLowerCase(Locale.ROOT);
        String label = name.toLowerCase(Locale.ROOT);

        if (mail.equals(term) || label.equals(term)) {
            return 1000 + baseScore();
        }
        if (mail.startsWith(term)) {
            return 800 + baseScore();
        }
        if (label.startsWith(term)) {
            return 700 + baseScore();
        }
        if (mail.contains(term)) {
            return 600 + baseScore();
        }
        if (label.contains(term)) {
            return 500 + baseScore();
        }
        return Integer.MIN_VALUE;
    }

    /** The weight of the source alone, used as a tie breaker. */
    public int baseScore() {
        return switch (source) {
            case CONTACT -> 30;
            case RECENT -> 20;
            case ACCOUNT -> 10;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RecipientSuggestion other)) {
            return false;
        }
        return email.equalsIgnoreCase(other.email);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return displayLabel();
    }
}
