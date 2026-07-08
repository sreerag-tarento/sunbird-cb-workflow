package org.sunbird.workflow.consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.service.impl.ApplicationProcessingServiceImplV2;
import org.sunbird.workflow.service.impl.WorkflowAuditProcessingServiceImpl;
import org.sunbird.workflow.service.impl.WorkflowESSyncServiceImpl;

class ApplicationProcessingConsumerV2Test {

    @InjectMocks
    private ApplicationProcessingConsumerV2 consumer;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ApplicationProcessingServiceImplV2 appProcessingService;

    @Mock
    private WorkflowAuditProcessingServiceImpl auditService;

    @Mock
    private WorkflowESSyncServiceImpl esSyncService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testProcessMessage_validPayload() throws Exception {
        String json = "{\"userId\":\"user123\",\"serviceName\":\"testService\",\"workflowRequests\":[{\"id\":\"req1\"}]}";
        ConsumerRecord<String, String> cRecord = new ConsumerRecord<>("test-topic", 0, 0L, null, json);

        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put(Constants.USER_ID, "user123");
        eventMap.put(Constants.SERVICE_NAME, "testService");
        List<WfRequest> wfRequests = List.of(new WfRequest());

        eventMap.put(Constants.WORKFLOW_REQUESTS, wfRequests);

        when(objectMapper.readValue(eq(json), ArgumentMatchers.<TypeReference<Map<String, Object>>>any()))
                .thenReturn(eventMap);
        when(objectMapper.convertValue(eq(wfRequests), ArgumentMatchers.<TypeReference<List<WfRequest>>>any()))
                .thenReturn(wfRequests);

        assertDoesNotThrow(() -> consumer.processMessage(cRecord));
    }

    @Test
    void testProcessMessage_blankPayload() {
        ConsumerRecord<String, String> cRecord = new ConsumerRecord<>("test-topic", 0, 0L, null, " ");
        assertDoesNotThrow(() ->consumer.processMessage(cRecord));
        // should only log error, no exception
    }

    @Test
    void testProcessMessage_invalidJson() throws Exception {
        String invalidJson = "{invalid json}";
        ConsumerRecord<String, String> cRecord = new ConsumerRecord<>("test-topic", 0, 0L, null, invalidJson);

        when(objectMapper.readValue(eq(invalidJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any()))
                .thenThrow(new RuntimeException("Parse error"));

        assertDoesNotThrow(() ->consumer.processMessage(cRecord));

    }

    @Test
    void testProcessMessage_classCastException() throws Exception {
        String json = "{\"workflowRequests\":\"not a list\"}";
        ConsumerRecord<String, String> cRecord = new ConsumerRecord<>("test-topic", 0, 0L, null, json);

        Map<String, Object> eventMap = new HashMap<>();
        eventMap.put(Constants.USER_ID, "user123");
        eventMap.put(Constants.SERVICE_NAME, "testService");
        eventMap.put(Constants.WORKFLOW_REQUESTS, "invalid");

        when(objectMapper.readValue(eq(json), ArgumentMatchers.<TypeReference<Map<String, Object>>>any()))
                .thenReturn(eventMap);
        when(objectMapper.convertValue(any(), ArgumentMatchers.<TypeReference<List<WfRequest>>>any()))
                .thenThrow(new ClassCastException("bad structure"));

        assertDoesNotThrow(() ->consumer.processMessage(cRecord));
    }

    @Test
    void testProcessApplicationMessage_internalException() throws Exception {
        String json = "{\"userId\":\"user123\",\"serviceName\":\"testService\",\"workflowRequests\":[{\"id\":\"req1\"}]}";
        ConsumerRecord<String, String> cRecord = new ConsumerRecord<>("test-topic", 0, 0L, null, json);

        Map<String, Object> eventMap = new HashMap<>();
        List<WfRequest> wfRequests = List.of(new WfRequest());
        eventMap.put(Constants.USER_ID, "user123");
        eventMap.put(Constants.SERVICE_NAME, "testService");
        eventMap.put(Constants.WORKFLOW_REQUESTS, wfRequests);

        when(objectMapper.readValue(eq(json), ArgumentMatchers.<TypeReference<Map<String, Object>>>any()))
                .thenReturn(eventMap);
        when(objectMapper.convertValue(eq(wfRequests), ArgumentMatchers.<TypeReference<List<WfRequest>>>any()))
                .thenReturn(wfRequests);

        doThrow(new RuntimeException("internal error"))
                .when(appProcessingService).processWfApplicationRequest(any(), anyString(), anyString(), any());

        assertDoesNotThrow(() ->consumer.processMessage(cRecord));
    }

}
