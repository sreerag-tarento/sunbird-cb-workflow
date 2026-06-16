package org.sunbird.workflow.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.sunbird.workflow.config.WorkflowRedisCacheMgr;
import org.sunbird.workflow.models.BatchStatsEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class BatchStatsConsumerTest {

    private static final String BATCH_ID = "batch1";
    private static final String BATCH_ID_X = "batchX";
    private static final String FIELD_PENDING = "pending";
    private static final String FIELD_WITHDRAWN = "withdrawn";
    private static final String TOPIC = "bp.batch.enrollment.stats";

    @Mock
    private ObjectMapper mapper;

    @Mock
    private WorkflowRedisCacheMgr workflowRedisCacheMgr;

    private BatchStatsConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new BatchStatsConsumer(mapper, workflowRedisCacheMgr);
    }

    private ConsumerRecord<String, String> createRecord(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 42L, null, value);
    }

    @Test
    void processMessage_positiveDelta_callsIncrement() throws Exception {
        String payload = "{\"batchId\":\"batch1\",\"field\":\"pending\",\"delta\":1}";
        BatchStatsEvent event = new BatchStatsEvent(BATCH_ID, FIELD_PENDING, 1L);
        when(mapper.readValue(payload, BatchStatsEvent.class)).thenReturn(event);
        consumer.processMessage(createRecord(payload));
        verify(workflowRedisCacheMgr).incrementBatchFieldCount(BATCH_ID, FIELD_PENDING);
        verify(workflowRedisCacheMgr, never()).decrementBatchFieldCount(any(), any());
    }

    @Test
    void processMessage_negativeDelta_callsDecrement() throws Exception {
        String payload = "{\"batchId\":\"batch1\",\"field\":\"pending\",\"delta\":-1}";
        BatchStatsEvent event = new BatchStatsEvent(BATCH_ID, FIELD_PENDING, -1L);
        when(mapper.readValue(payload, BatchStatsEvent.class)).thenReturn(event);
        consumer.processMessage(createRecord(payload));
        verify(workflowRedisCacheMgr).decrementBatchFieldCount(BATCH_ID, FIELD_PENDING);
        verify(workflowRedisCacheMgr, never()).incrementBatchFieldCount(any(), any());
    }

    @Test
    void processMessage_zeroDelta_callsNeither() throws Exception {
        String payload = "{\"batchId\":\"batch1\",\"field\":\"pending\",\"delta\":0}";
        BatchStatsEvent event = new BatchStatsEvent(BATCH_ID, FIELD_PENDING, 0L);
        when(mapper.readValue(payload, BatchStatsEvent.class)).thenReturn(event);
        consumer.processMessage(createRecord(payload));
        verifyNoInteractions(workflowRedisCacheMgr);
    }

    @Test
    void processMessage_nullValue_doesNotThrowAndSkipsProcessing() {
        assertDoesNotThrow(() -> consumer.processMessage(createRecord(null)));
        verifyNoInteractions(workflowRedisCacheMgr);
    }

    @Test
    void processMessage_blankValue_doesNotThrowAndSkipsProcessing() {
        assertDoesNotThrow(() -> consumer.processMessage(createRecord("   ")));
        verifyNoInteractions(workflowRedisCacheMgr);
    }

    @Test
    void processMessage_mapperThrowsException_doesNotPropagate() throws Exception {
        String badPayload = "{invalid}";
        when(mapper.readValue(badPayload, BatchStatsEvent.class))
                .thenThrow(new RuntimeException("parse error"));
        assertDoesNotThrow(() -> consumer.processMessage(createRecord(badPayload)));
        verifyNoInteractions(workflowRedisCacheMgr);
    }

    @Test
    void processMessage_withdrawnFieldWithNegativeDelta_callsDecrementWithCorrectArgs() throws Exception {
        String payload = "{\"batchId\":\"batchX\",\"field\":\"withdrawn\",\"delta\":-1}";
        BatchStatsEvent event = new BatchStatsEvent(BATCH_ID_X, FIELD_WITHDRAWN, -1L);
        when(mapper.readValue(payload, BatchStatsEvent.class)).thenReturn(event);
        consumer.processMessage(createRecord(payload));
        verify(workflowRedisCacheMgr).decrementBatchFieldCount(BATCH_ID_X, FIELD_WITHDRAWN);
    }

    @Test
    void processMessage_withdrawnFieldWithPositiveDelta_callsIncrementWithCorrectArgs() throws Exception {
        String payload = "{\"batchId\":\"batchX\",\"field\":\"withdrawn\",\"delta\":1}";
        BatchStatsEvent event = new BatchStatsEvent(BATCH_ID_X, FIELD_WITHDRAWN, 1L);
        when(mapper.readValue(payload, BatchStatsEvent.class)).thenReturn(event);
        consumer.processMessage(createRecord(payload));
        verify(workflowRedisCacheMgr).incrementBatchFieldCount(BATCH_ID_X, FIELD_WITHDRAWN);
    }
}
