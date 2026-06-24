package org.sunbird.workflow.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.sunbird.workflow.config.WorkflowRedisCacheMgr;
import org.sunbird.workflow.models.BatchStatsEvent;

/**
 * Kafka consumer for blended program batch enrollment stats.
 * Listens on the bp.batch.enrollment.stats topic and applies
 * Redis HINCRBY operations asynchronously, decoupling Redis writes
 * from the enrollment/withdrawal request path.
 */
@Service
public class BatchStatsConsumer {

    private static final Logger logger = LogManager.getLogger(BatchStatsConsumer.class);

    private final ObjectMapper mapper;
    private final WorkflowRedisCacheMgr workflowRedisCacheMgr;

    public BatchStatsConsumer(ObjectMapper mapper, WorkflowRedisCacheMgr workflowRedisCacheMgr) {
        this.mapper = mapper;
        this.workflowRedisCacheMgr = workflowRedisCacheMgr;
    }

    /**
     * Deserialises the incoming {@link BatchStatsEvent} and routes to the
     * appropriate cache operation based on the delta sign:
     * positive delta → increment, negative delta → decrement.
     */
    @KafkaListener(topics = "${kafka.topics.bp.batch.stats}", groupId = "bp-batch-stats-consumer")
    public void processMessage(ConsumerRecord<String, String> data) {
        try {
            if (data.value() == null || data.value().isBlank()) {
                logger.warn("Empty message received in BatchStatsConsumer at offset={} partition={}",
                        data.offset(), data.partition());
                return;
            }
            var event = mapper.readValue(data.value(), BatchStatsEvent.class);
            logger.debug("Processing BatchStatsEvent batchId={} field={} delta={}",
                    event.getBatchId(), event.getField(), event.getDelta());
            if (event.getDelta() > 0) {
                workflowRedisCacheMgr.incrementBatchFieldCount(event.getBatchId(), event.getField());
            } else if (event.getDelta() < 0) {
                workflowRedisCacheMgr.decrementBatchFieldCount(event.getBatchId(), event.getField());
            } else {
                logger.warn("Received BatchStatsEvent with delta=0 for batchId={}, ignoring", event.getBatchId());
            }
        } catch (Exception e) {
            logger.error("Error processing BatchStatsEvent payload={} error={}", data.value(), e.getMessage(), e);
        }
    }
}
