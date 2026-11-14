/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.filter;

import java.util.Locale;
import java.util.Objects;

/** One match rule inside a message filter. */
public final class FilterCondition {
    public enum Field {
        FROM, TO, SUBJECT, SNIPPET, HAS_ATTACHMENT, SIZE_LARGER_KB
    }

    public enum Operator {
        CONTAINS, NOT_CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH
    }

    private final Field field;
    private final Operator operator;
    private final String value;

    public FilterCondition(Field field, Operator operator, String value) {
        this.field = Objects.requireNonNull(field, "field");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.value = value == null ? "" : value;
    }

    public Field field() { return field; }

    public Operator operator() { return operator; }

    public String value() { return value; }

    /** Case-insensitive match helper shared by the engine. */
    public boolean matchesText(String actual) {
        String haystack = actual == null ? "" : actual.toLowerCase(Locale.ROOT);
        String needle = value.toLowerCase(Locale.ROOT);
        return switch (operator) {
            case CONTAINS -> haystack.contains(needle);
            case NOT_CONTAINS -> !haystack.contains(needle);
            case EQUALS -> haystack.equals(needle);
            case STARTS_WITH -> haystack.startsWith(needle);
            case ENDS_WITH -> haystack.endsWith(needle);
        };
    }
}
