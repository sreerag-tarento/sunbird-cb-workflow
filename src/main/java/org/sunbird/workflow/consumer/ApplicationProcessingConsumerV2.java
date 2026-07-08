package org.sunbird.workflow.consumer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.service.impl.ApplicationProcessingServiceImplV2;
import org.sunbird.workflow.service.impl.WorkflowAuditProcessingServiceImpl;
import org.sunbird.workflow.service.impl.WorkflowESSyncServiceImpl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ApplicationProcessingConsumerV2 {

    Logger logger = LogManager.getLogger(ApplicationProcessingConsumer.class);

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ApplicationProcessingServiceImplV2 applicationProcessingServiceImplV2;

    @Autowired
    private WorkflowAuditProcessingServiceImpl workflowAuditProcessingService;
    
    @Autowired
    private WorkflowESSyncServiceImpl workflowESSyncServiceImpl;

    @KafkaListener(groupId = "workflowContentTopic-consumer-v2", topics = "${kafka.topics.workflow.request.v2}")
    public void processMessage(ConsumerRecord<String, String> data) {
        try {
            if (StringUtils.isNoneBlank(data.value())) {
                CompletableFuture.runAsync(() -> {
                    processApplicationMessage(data.value());
                });
            } else {
                logger.error("Error in Application Consumer: Invalid Kafka Msg");
            }
        } catch (Exception e) {
            logger.error(String.format("Error in Application Consumer: Error Msg :%s", e.getMessage()), e);
        }
    }

    private void processApplicationMessage(String strData) {
        logger.info("Received data in user profile consumer: {}", strData);
        try {
            Map<String, Object> workflowEventObj = mapper.readValue(strData, new TypeReference<Map<String, Object>>() {
            });
            String userId = (String) workflowEventObj.get(Constants.USER_ID);
            String serviceName = (String) workflowEventObj.get(Constants.SERVICE_NAME);
            String userToken = (String) workflowEventObj.get(Constants.X_AUTH_TOKEN);
            @SuppressWarnings("unchecked")
            List<WfRequest> wfRequests = mapper.convertValue(workflowEventObj.get(Constants.WORKFLOW_REQUESTS),
                    new TypeReference<List<WfRequest>>() {
                    });

            try {
                logger.debug("Processing workflow request for user ID: {}", userId);

                applicationProcessingServiceImplV2.processWfApplicationRequest(wfRequests, serviceName, userId, userToken);
                applicationProcessingServiceImplV2.updateDepartmentToPortalDBs(wfRequests);
                createAudit(wfRequests);
                wfRequests.forEach(wfRequest -> {
                    workflowESSyncServiceImpl.syncWithElasticService(wfRequest);
                });
                logger.debug("Successfully processed workflow request with user ID: {}", userId);
            } catch (Exception e) {
                logger.error("Error processing workflow request with wf ID: {}", userId, e);
            }
        } catch (IOException e) {
            logger.error("Error deserializing workflow event data: {}", strData, e);
        } catch (ClassCastException e) {
            logger.error("Invalid data structure in workflow event: {}", strData, e);
        } catch (Exception e) {
            logger.error("Unexpected error while processing application message: {}", strData, e);
        }
    }

    private void createAudit(List<WfRequest> wfRequestList) {
        wfRequestList.forEach(wfRequest -> workflowAuditProcessingService.createAudit(wfRequest));
    }
}
