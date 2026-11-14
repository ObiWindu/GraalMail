/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.filter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A user-defined rule that acts on incoming mail.
 *
 * <p>Filters are evaluated in {@code priority} order (lowest first).
 * When {@code matchAll} is true every condition must match, otherwise any
 * single matching condition fires the filter.</p>
 */
public final class MessageFilter {
    private final String id;
    private final String accountId;
    private final String name;
    private final boolean enabled;
    private final boolean matchAll;
    private final int priority;
    private final List<FilterCondition> conditions;
    private final List<FilterAction> actions;
    private final Instant updatedAt;

    public MessageFilter(String id, String accountId, String name, boolean enabled,
                         boolean matchAll, int priority,
                         List<FilterCondition> conditions, List<FilterAction> actions,
                         Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.accountId = accountId == null ? "" : accountId;
        this.name = name == null ? "" : name;
        this.enabled = enabled;
        this.matchAll = matchAll;
        this.priority = priority;
        this.conditions = List.copyOf(conditions == null ? List.of() : conditions);
        this.actions = List.copyOf(actions == null ? List.of() : actions);
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public String id() { return id; }

    /** Empty means the filter applies to every account. */
    public String accountId() { return accountId; }

    public String name() { return name; }

    public boolean enabled() { return enabled; }

    public boolean matchAll() { return matchAll; }

    public int priority() { return priority; }

    public List<FilterCondition> conditions() { return conditions; }

    public List<FilterAction> actions() { return actions; }

    public Instant updatedAt() { return updatedAt; }

    public boolean appliesToAccount(String account) {
        return accountId.isEmpty() || accountId.equals(account == null ? "" : account);
    }

    /** Filters sorted for evaluation (priority, then name). */
    public static List<MessageFilter> sorted(List<MessageFilter> filters) {
        List<MessageFilter> copy = new ArrayList<>(filters == null ? List.of() : filters);
        copy.sort(Comparator.comparingInt(MessageFilter::priority).thenComparing(MessageFilter::name));
        return copy;
    }
}
