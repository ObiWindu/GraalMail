/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core.filter;

import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure evaluation of message filters against cached messages.
 *
 * <p>The engine never touches the network or the database: callers apply the
 * returned decisions (queue pending actions, update the local cache).</p>
 */
public final class FilterEngine {

    private FilterEngine() {
    }

    /** A filter that matched a message together with its actions. */
    public record FilterHit(MessageFilter filter, List<FilterAction> actions) {
    }

    /**
     * Evaluates every enabled filter for one message, in priority order.
     *
     * @param message the message, never {@code null}
     * @param filters the filters, may be {@code null}
     * @return the matching filters with their actions
     */
    public static List<FilterHit> evaluate(Message message, List<MessageFilter> filters) {
        List<FilterHit> hits = new ArrayList<>();
        if (message == null || filters == null) {
            return hits;
        }
        for (MessageFilter filter : MessageFilter.sorted(filters)) {
            if (!filter.enabled() || filter.conditions().isEmpty() || filter.actions().isEmpty()) {
                continue;
            }
            if (!filter.appliesToAccount(message.accountId())) {
                continue;
            }
            if (matches(message, filter)) {
                hits.add(new FilterHit(filter, filter.actions()));
            }
        }
        return hits;
    }

    /**
     * All actions that fire for a message, de-duplicated (move wins once).
     *
     * @param message the message
     * @param filters the filters
     * @return the actions in evaluation order
     */
    public static List<FilterAction> actionsFor(Message message, List<MessageFilter> filters) {
        List<FilterAction> actions = new ArrayList<>();
        boolean moveTaken = false;
        for (FilterHit hit : evaluate(message, filters)) {
            for (FilterAction action : hit.actions()) {
                if (action.type() == FilterAction.Type.MOVE_TO_FOLDER) {
                    if (moveTaken || action.targetFolderId().isBlank()) {
                        continue;
                    }
                    moveTaken = true;
                }
                actions.add(action);
            }
        }
        return actions;
    }

    static boolean matches(Message message, MessageFilter filter) {
        List<Boolean> results = filter.conditions().stream()
                .map(condition -> matchesCondition(message, condition))
                .collect(Collectors.toList());
        if (filter.matchAll()) {
            return results.stream().allMatch(Boolean::booleanValue);
        }
        return results.stream().anyMatch(Boolean::booleanValue);
    }

    static boolean matchesCondition(Message message, FilterCondition condition) {
        return switch (condition.field()) {
            case FROM -> condition.matchesText(displayable(message.from())
                    + " " + message.from().email());
            case TO -> condition.matchesText(emails(message.to()) + " " + emails(message.cc()));
            case SUBJECT -> condition.matchesText(message.subject());
            case SNIPPET -> condition.matchesText(message.snippet()
                    + " " + (message.bodyText() == null ? "" : message.bodyText()));
            case HAS_ATTACHMENT -> message.hasAttachments();
            case SIZE_LARGER_KB -> {
                long threshold;
                try {
                    threshold = Long.parseLong(condition.value().trim()) * 1024L;
                } catch (NumberFormatException e) {
                    yield false;
                }
                boolean larger = message.size() > threshold;
                yield condition.operator() == FilterCondition.Operator.NOT_CONTAINS ? !larger : larger;
            }
        };
    }

    private static String displayable(Address address) {
        if (address == null) {
            return "";
        }
        return address.name() + " " + address.displayLabel();
    }

    private static String emails(List<Address> addresses) {
        if (addresses == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Address address : addresses) {
            sb.append(address.email()).append(' ').append(address.name()).append(' ');
        }
        return sb.toString();
    }
}
