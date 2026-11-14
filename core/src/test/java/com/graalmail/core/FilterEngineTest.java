/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import com.graalmail.core.filter.FilterAction;
import com.graalmail.core.filter.FilterCondition;
import com.graalmail.core.filter.FilterEngine;
import com.graalmail.core.filter.MessageFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterEngineTest {

    @Test
    void matchesAnyByDefaultLogic() {
        Message message = message("1", "Invoice March", "billing@example.com");
        MessageFilter filter = new MessageFilter("f1", "", "Billing", true, false, 10,
                List.of(new FilterCondition(FilterCondition.Field.FROM,
                        FilterCondition.Operator.CONTAINS, "billing")),
                List.of(FilterAction.markRead()), Instant.now());
        assertEquals(1, FilterEngine.evaluate(message, List.of(filter)).size());
        assertEquals(1, FilterEngine.actionsFor(message, List.of(filter)).size());
    }

    @Test
    void matchAllRequiresEveryCondition() {
        Message message = message("1", "Invoice March", "billing@example.com");
        MessageFilter filter = new MessageFilter("f1", "", "Strict", true, true, 10,
                List.of(
                        new FilterCondition(FilterCondition.Field.FROM,
                                FilterCondition.Operator.CONTAINS, "billing"),
                        new FilterCondition(FilterCondition.Field.SUBJECT,
                                FilterCondition.Operator.CONTAINS, "April")),
                List.of(FilterAction.markRead()), Instant.now());
        assertTrue(FilterEngine.evaluate(message, List.of(filter)).isEmpty());
    }

    @Test
    void disabledFiltersNeverFire() {
        Message message = message("1", "Hello", "a@example.com");
        MessageFilter filter = new MessageFilter("f1", "", "Off", false, false, 10,
                List.of(new FilterCondition(FilterCondition.Field.SUBJECT,
                        FilterCondition.Operator.CONTAINS, "Hello")),
                List.of(FilterAction.delete()), Instant.now());
        assertTrue(FilterEngine.evaluate(message, List.of(filter)).isEmpty());
    }

    @Test
    void onlyFirstMoveWins() {
        Message message = message("1", "Hello", "a@example.com");
        MessageFilter first = new MessageFilter("f1", "", "A", true, false, 1,
                List.of(new FilterCondition(FilterCondition.Field.FROM,
                        FilterCondition.Operator.CONTAINS, "a@")),
                List.of(FilterAction.moveTo("Archive")), Instant.now());
        MessageFilter second = new MessageFilter("f2", "", "B", true, false, 2,
                List.of(new FilterCondition(FilterCondition.Field.FROM,
                        FilterCondition.Operator.CONTAINS, "a@")),
                List.of(FilterAction.moveTo("Spam")), Instant.now());
        List<FilterAction> actions = FilterEngine.actionsFor(message, List.of(second, first));
        long moves = actions.stream()
                .filter(a -> a.type() == FilterAction.Type.MOVE_TO_FOLDER)
                .count();
        assertEquals(1, moves);
        assertEquals("Archive", actions.get(0).targetFolderId());
    }

    @Test
    void accountScopedFilter() {
        Message message = Message.builder("1").accountId("acc1")
                .subject("Hello").from(new Address("", "a@example.com")).build();
        MessageFilter scoped = new MessageFilter("f1", "acc2", "Other", true, false, 1,
                List.of(new FilterCondition(FilterCondition.Field.SUBJECT,
                        FilterCondition.Operator.CONTAINS, "Hello")),
                List.of(FilterAction.markRead()), Instant.now());
        assertTrue(FilterEngine.evaluate(message, List.of(scoped)).isEmpty());
    }

    private static Message message(String id, String subject, String from) {
        return Message.builder(id)
                .subject(subject)
                .from(new Address("", from))
                .date(Instant.now())
                .build();
    }
}
