package org.sunbird.workflow.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka event payload for asynchronous batch enrollment stat updates.
 * delta > 0 → increment the field; delta < 0 → decrement the field.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchStatsEvent {

    private String batchId;
    private String field;
    private long delta;
}
