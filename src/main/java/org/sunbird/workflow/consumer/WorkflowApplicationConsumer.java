package org.sunbird.workflow.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.AiAssessmentApprovalEvent;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.producer.Producer;
import org.sunbird.workflow.service.Workflowservice;
import org.sunbird.workflow.service.impl.RequestServiceImpl;
import org.sunbird.workflow.utils.AccessTokenValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class WorkflowApplicationConsumer {
    Logger logger = LogManager.getLogger(WorkflowApplicationConsumer.class);

    static final RestTemplate restTemplate = new RestTemplate();

    private final Producer producer;
    private final RequestServiceImpl requestServiceImpl;
    private final Configuration configuration;
    private final ObjectMapper mapper;

    public WorkflowApplicationConsumer(Producer producer, RequestServiceImpl requestServiceImpl, Configuration configuration, ObjectMapper mapper) {
        this.producer = producer;
        this.requestServiceImpl = requestServiceImpl;
        this.configuration = configuration;
        this.mapper = mapper;
    }

    @KafkaListener(groupId = "aiAssessmentTopic-consumer", topics = "${ai.assessment.topic}")
    public void processAiAssessmentMessage(ConsumerRecord<String, String> data) {

        if (data == null || StringUtils.isBlank(data.value())) {
            logger.error("Invalid Kafka message received for AI Assessment");
            return;
        }

        CompletableFuture.runAsync(() ->
                processAiAssessmentApproval(data.value()));
    }

    private void processAiAssessmentApproval(String strData) {
        try {
            AiAssessmentApprovalEvent event = mapper.readValue(strData, AiAssessmentApprovalEvent.class);
            WfRequest wfRequest = event.getWfRequest();
            logger.info("Received AI Assessment APPROVED for userId: {}",
                    wfRequest.getUserId());
            List<String> existingRoles = fetchUserRoles(wfRequest.getUserId(),
                    wfRequest.getRootOrgId());
            logger.info("Existing roles for userId: {} are: {}",
                    wfRequest.getUserId(), existingRoles);
            if (!existingRoles.contains(Constants.AI_ASSESSMENT_CREATOR)) {
                existingRoles.add(Constants.AI_ASSESSMENT_CREATOR);
            } else {
                logger.info("User {} already has AI_ASSESSMENT_CREATOR role",
                        wfRequest.getUserId());
            }

            Map<String, Object> requestMap = new HashMap<>();
            Map<String, Object> innerRequest = new HashMap<>();
            innerRequest.put(Constants.USER_ID, wfRequest.getUserId());
            innerRequest.put(Constants.ORGANIZATION_ID, wfRequest.getRootOrgId());
            innerRequest.put(Constants.ROLES, existingRoles);
            requestMap.put(Constants.REQUEST, innerRequest);

            String requestBody = mapper.writeValueAsString(requestMap);
            logger.info("Role assign request body: {}", requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StringUtils.isNotBlank(event.getXAuthToken())) {
                headers.set(Constants.X_AUTH_TOKEN, event.getXAuthToken());
            }
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    configuration.getLmsServiceHost()
                            + configuration.getLmsAssignRoleEndPoint(),
                    entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Role assigned successfully for userId: {}",
                        wfRequest.getUserId());
                producer.push(configuration.getWorkFlowNotificationTopic(), wfRequest);
            } else {
                logger.error("Role assign failed for userId: {} status: {}",
                        wfRequest.getUserId(), response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("Error processing AI Assessment approval, error: {}",
                    e.getMessage(), e);
        }
    }


    private List<String> fetchUserRoles(String userId, String rootOrgId) {
        try {
            StringBuilder url = new StringBuilder(
                    configuration.getLmsServiceHost())
                    .append(configuration.getLmsUserSearchEndPoint());

            Map<String, Object> filters = new HashMap<>();
            filters.put(Constants.USER_ID, userId);
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.FILTERS, filters);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(Constants.REQUEST, request);

            Map<String, Object> response = (Map<String, Object>) requestServiceImpl.fetchResultUsingPost(url, requestBody, Map.class, null);

            if (response != null
                    && Constants.OK.equalsIgnoreCase(
                    (String) response.get(Constants.RESPONSE_CODE))) {

                Map<String, Object> result = (Map<String, Object>) response
                        .get(Constants.RESULT);
                Map<String, Object> responseMap = (Map<String, Object>) result
                        .get(Constants.RESPONSE);
                List<Map<String, Object>> content = (List<Map<String, Object>>) responseMap
                        .get(Constants.CONTENT);

                if (!CollectionUtils.isEmpty(content)) {
                    List<Map<String, Object>> organisations = (List<Map<String, Object>>) content
                            .get(0).get(Constants.ORGANISATIONS);

                    if (!CollectionUtils.isEmpty(organisations)) {
                        for (Map<String, Object> org : organisations) {
                            if (rootOrgId.equalsIgnoreCase(
                                    (String) org.get(Constants.ORGANIZATION_ID))) {
                                List<String> roles = (List<String>) org.get(Constants.ROLES);
                                return roles != null ? new ArrayList<>(roles)
                                        : new ArrayList<>();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching roles for userId: {}, error: {}",
                    userId, e.getMessage(), e);
        }
        return new ArrayList<>();
    }
}