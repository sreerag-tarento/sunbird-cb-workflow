package org.sunbird.workflow.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BatchStatsEventTest {

    @Test
    void allArgsConstructor_setsAllFields() {
        BatchStatsEvent event = new BatchStatsEvent("batch1", "pending", 1L);

        assertEquals("batch1", event.getBatchId());
        assertEquals("pending", event.getField());
        assertEquals(1L, event.getDelta());
    }

    @Test
    void noArgsConstructor_fieldsAreDefault() {
        BatchStatsEvent event = new BatchStatsEvent();
        assertNull(event.getBatchId());
        assertNull(event.getField());
        assertEquals(0L, event.getDelta());
    }

    @Test
    void setters_updateFields() {
        BatchStatsEvent event = new BatchStatsEvent();
        event.setBatchId("batch99");
        event.setField("withdrawn");
        event.setDelta(-1L);
        assertEquals("batch99", event.getBatchId());
        assertEquals("withdrawn", event.getField());
        assertEquals(-1L, event.getDelta());
    }

    @Test
    void negativeDelta_representsDecrement() {
        BatchStatsEvent event = new BatchStatsEvent("b1", "pending", -1L);
        assertEquals(-1L, event.getDelta());
    }

    @Test
    void positiveDelta_representsIncrement() {
        BatchStatsEvent event = new BatchStatsEvent("b1", "withdrawn", 1L);
        assertEquals(1L, event.getDelta());
    }

    @Test
    void zeroDelta_isIgnoredByConsumer() {
        BatchStatsEvent event = new BatchStatsEvent("b1", "pending", 0L);
        assertEquals(0L, event.getDelta());
    }
}
