package org.sunbird.workflow.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.models.SearchCriteria;
import org.sunbird.workflow.service.WorkFlowServiceV2;
import org.sunbird.workflow.service.Workflowservice;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

class WorkFlowControllerV2Test {

    @InjectMocks
    private WorkFlowControllerV2 workFlowControllerV2;

    @Mock
    private Workflowservice workflowService;

    @Mock
    private WorkFlowServiceV2 workFlowServiceV2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetUserWFApplicationFieldsV2() {
        // Arrange
        String rootOrg = "testRootOrg";
        String org = "testOrg";
        String wid = "user-123";
        SearchCriteria searchCriteria = new SearchCriteria();

        Response expectedResponse = new Response();
        expectedResponse.setResponseCode(HttpStatus.OK);

        when(workflowService.getUserWFApplicationFieldsV2(rootOrg, org, wid, searchCriteria))
                .thenReturn(expectedResponse);

        // Act
        ResponseEntity<Response> responseEntity = workFlowControllerV2.getUserWFApplicationFieldsV2(
                rootOrg, org, wid, searchCriteria);

        // Assert
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(expectedResponse, responseEntity.getBody());
    }

    @Test
    void testWfTransition() {
        // Arrange
        String rootOrg = "testRootOrg";
        String org = "testOrg";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("wfId", "wf-001");

        String userAuthToken = "test-auth-token";
        Response expectedResponse = new Response();
        expectedResponse.setResponseCode(HttpStatus.OK);

        when(workFlowServiceV2.workflowTransition(rootOrg, org, requestBody, userAuthToken))
                .thenReturn(expectedResponse);

        // Act
        ResponseEntity<Response> responseEntity = workFlowControllerV2.wfTransition(
                rootOrg, org, requestBody, userAuthToken);

        // Assert
        assertEquals(HttpStatus.OK, responseEntity.getStatusCode());
        assertEquals(expectedResponse, responseEntity.getBody());
    }
}
