/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.graalmail.core.filter.FilterAction;
import com.graalmail.core.filter.FilterCondition;
import com.graalmail.core.filter.MessageFilter;
import com.graalmail.core.filter.MessageTemplate;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilterTemplateSnoozeStoreTest {

    private SqlMailStore store;

    @BeforeEach
    void setUp() {
        store = new SqlMailStore(new JdbcSqlDatabase("jdbc:sqlite::memory:"));
        store.initialize();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void savesAndListsFilters() {
        MessageFilter filter = new MessageFilter("f1", "", "Billing", true, true, 5,
                List.of(new FilterCondition(FilterCondition.Field.FROM,
                        FilterCondition.Operator.CONTAINS, "billing")),
                List.of(FilterAction.moveTo("Archive"), FilterAction.markRead()),
                Instant.now());
        store.saveFilter(filter);
        List<MessageFilter> loaded = store.listFilters();
        assertEquals(1, loaded.size());
        assertEquals("Billing", loaded.get(0).name());
        assertEquals(1, loaded.get(0).conditions().size());
        assertEquals(2, loaded.get(0).actions().size());
        store.deleteFilter("f1");
        assertTrue(store.listFilters().isEmpty());
    }

    @Test
    void savesAndListsTemplates() {
        MessageTemplate template = new MessageTemplate("t1", "", "Thanks",
                "Re: Thanks", "Thanks for writing!", Instant.now());
        store.saveTemplate(template);
        assertEquals(1, store.listTemplates().size());
        assertEquals("Thanks for writing!", store.listTemplates().get(0).bodyText());
        store.deleteTemplate("t1");
        assertTrue(store.listTemplates().isEmpty());
    }

    @Test
    void snoozesAndUnsnoozes() {
        Instant wake = Instant.now().plusSeconds(3600);
        store.snoozeMessage("m1", "a1", wake);
        assertEquals(1, store.listSnoozed().size());
        assertEquals(wake.toEpochMilli(), store.listSnoozed().get(0).wakeAt().toEpochMilli());
        store.unsnoozeMessage("m1");
        assertTrue(store.listSnoozed().isEmpty());
    }
}
