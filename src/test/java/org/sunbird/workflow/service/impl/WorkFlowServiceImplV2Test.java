package org.sunbird.workflow.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.RedisCacheMgr;
import org.sunbird.workflow.exception.ApplicationException;
import org.sunbird.workflow.exception.BadRequestException;
import org.sunbird.workflow.models.*;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.utils.AccessTokenValidator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkFlowServiceImplV2Test {

    @InjectMocks
    private WorkFlowServiceImplV2 service;

    @Mock private WfStatusRepo wfStatusRepo;
    @Mock private ObjectMapper mapper;
    @Mock private Configuration configuration;
    @Mock private RequestServiceImpl requestServiceImpl;
    @Mock private RedisCacheMgr redisCacheMgr;
    @Mock private AccessTokenValidator accessTokenValidator;
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testWorkflowTransition_validRequest() throws Exception {
        Map<String, Object> requestBody = new HashMap<>();
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");
        wfRequest.setServiceName("PROFILE_SERVICE");
        wfRequest.setWfId("wf123");
        wfRequest.setState("PENDING");
        wfRequest.setAction("APPROVE");
        wfRequest.setActorUserId("actor1");
        wfRequest.setApplicationId("app1");

        HashMap<String, Object> fromValue = new HashMap<>();
        fromValue.put("name", "oldDept");

        HashMap<String, Object> toValue = new HashMap<>();
        toValue.put("name", "newDept");

        HashMap<String, Object> updatedMap = new HashMap<>();
        updatedMap.put("group", "professionalDetails");
        updatedMap.put("fromValue", fromValue);
        updatedMap.put("toValue", toValue);

        wfRequest.setUpdateFieldValues(List.of(updatedMap));

        requestBody.put(Constants.REQUEST, List.of(wfRequest));

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("requestUser1");
        when(wfStatusRepo.findByRootOrgAndOrgAndApplicationIdAndWfId(any(), any(), any(), any())).thenReturn(null);
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(Map.of(
                Constants.RESULT, Map.of(Constants.RESPONSE, Map.of(Constants.VALUE, "{}"))
        ));
        when(mapper.readValue(anyString(), any(Class.class))).thenReturn(Map.of());
        when(mapper.convertValue(any(), any(TypeReference.class))).thenReturn(List.of(wfRequest));
        when(mapper.convertValue(any(), eq(WorkFlowModel.class))).thenReturn(new WorkFlowModel());

        WorkFlowModel model = new WorkFlowModel();
        WfAction action1 = new WfAction();
        action1.setAction("REJECT");

        WfStatus wfStatus = new WfStatus();
        wfStatus.setState("PENDING");
        wfStatus.setActions(List.of(action1));
        model.setWfstates(List.of(wfStatus));
        when(mapper.convertValue(any(), any(TypeReference.class))).thenAnswer(invocation -> {
            TypeReference<?> typeRef = invocation.getArgument(1);

            if (typeRef.getType().getTypeName().contains("List")) {

                return List.of(wfRequest);
            } else if (typeRef.getType().getTypeName().contains("WorkFlowModel")) {
                WorkFlowModel wfModel = new WorkFlowModel();
                wfModel.setWfstates(List.of(new WfStatus()));

                return wfModel;
            } else {
                return null;
            }
        });

        Response response = service.workflowTransition("root", "org", requestBody, "test-auth-token");
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
    }

    @Test
    void testProcessAndUpdateProfessionalDetails() throws Exception {
        String userId = "user1";
        String json = """
            [
              {
                "fieldKey": "professionalDetails",
                "toValue": { "group": "group1", "designation": "designation1" }
              }
            ]
        """;

        JsonNode node = new ObjectMapper().readTree(json);
        when(mapper.readTree(json)).thenReturn(node);

       assertDoesNotThrow(()-> service.processAndUpdateProfessionalDetails(userId, json));
    }

    @Test
    void testUpdateProfessionalDetailsInCache_withCache() throws Exception {
        String userId = "user1";
        String group = "group1";
        String designation = "designation1";
        String cachedData = """
            {
              "professionalDetails": [ { "group": "old", "designation": "old" } ]
            }
        """;

        when(redisCacheMgr.getContentFromCache(any())).thenReturn(cachedData);
        ObjectNode node = (ObjectNode) new ObjectMapper().readTree(cachedData);
        when(mapper.readTree(cachedData)).thenReturn(node);

        service.updateProfessionalDetailsInCache(userId, group, designation);

        verify(redisCacheMgr).putInBasicProfileCache(any(), any(), anyInt());
    }

    @Test
    void testUpdateProfessionalDetailsInCache_noCache() {
        when(redisCacheMgr.getContentFromCache(any())).thenReturn(null);

        assertDoesNotThrow(()->service.updateProfessionalDetailsInCache("user1", "group", "designation"));

    }

    @Test
    void testGetWorkFlowConfig_profileService() throws Exception {
        when(configuration.getLmsServiceHost()).thenReturn("http://host");
        when(configuration.getProfileServiceConfigPath()).thenReturn("/profile/config");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(Map.of(
                Constants.RESULT, Map.of(Constants.RESPONSE, Map.of(Constants.VALUE, "{}"))
        ));
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(Map.of());
        when(mapper.convertValue(any(), any(TypeReference.class))).thenReturn(new WorkFlowModel());

        WorkFlowModel model = service.getWorkFlowConfig(Constants.PROFILE_SERVICE_NAME);
        assertNotNull(model);
    }

    @Test
    void testGetWorkFlowConfig_profileService_success() throws Exception {

        prepareConfigMocks(Constants.PROFILE_SERVICE_NAME, "/profile-path");

        Map<String, Object> wfConfig = buildMockWFConfig();

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(wfConfig);
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.PROFILE_SERVICE_NAME);

        assertNotNull(result);

        verify(configuration).getProfileServiceConfigPath();
        verify(requestServiceImpl).fetchResultUsingGet(any());
        verify(mapper).readValue(anyString(), eq(Map.class));
        verify(mapper).convertValue(any(), any(TypeReference.class));
    }

    @Test
    void testGetWorkFlowConfig_userRegistrationService_success() throws Exception {

        prepareConfigMocks(Constants.USER_REGISTRATION_SERVICE_NAME, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.USER_REGISTRATION_SERVICE_NAME);

        assertNotNull(result);
        verify(configuration).getUserRegistrationServiceConfigPath();
    }

    @Test
    void testGetWorkFlowConfig_position_success() throws Exception {

        prepareConfigMocks(Constants.POSITION_SERVICE_NAME, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.POSITION_SERVICE_NAME);

        assertNotNull(result);
        verify(configuration).getPositionServiceConfigPath();
    }

    @Test
    void testGetWorkFlowConfig_organisation_success() throws Exception {

        prepareConfigMocks(Constants.ORGANISATION_SERVICE_NAME, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.ORGANISATION_SERVICE_NAME);

        assertNotNull(result);
        verify(configuration).getOrgServiceConfigPath();
    }

    @Test
    void testGetWorkFlowConfig_domain_success() throws Exception {

        prepareConfigMocks(Constants.DOMAIN_SERVICE_NAME, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.DOMAIN_SERVICE_NAME);

        assertNotNull(result);
        verify(configuration).getDomainServiceConfigPath();
    }

    @Test
    void testGetWorkFlowConfig_blendedProgram_success() throws Exception {

        prepareConfigMocks(Constants.BLENDED_PROGRAM_SERVICE_NAME, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.BLENDED_PROGRAM_SERVICE_NAME);

        assertNotNull(result);
        verify(configuration).getBlendedProgramServicePath();
    }

    @Test
    void testGetWorkFlowConfig_oneStepPCApproval_success() throws Exception {

        prepareConfigMocks(Constants.ONE_STEP_PC_APPROVAL, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.ONE_STEP_PC_APPROVAL);

        assertNotNull(result);
    }

    @Test
    void testGetWorkFlowConfig_oneStepMDOApproval_success() throws Exception {

        prepareConfigMocks(Constants.ONE_STEP_MDO_APPROVAL, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.ONE_STEP_MDO_APPROVAL);

        assertNotNull(result);
    }

    @Test
    void testGetWorkFlowConfig_twoStepMDOAndPCApproval_success() throws Exception {

        prepareConfigMocks(Constants.TWO_STEP_MDO_AND_PC_APPROVAL, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.TWO_STEP_MDO_AND_PC_APPROVAL);

        assertNotNull(result);
    }

    @Test
    void testGetWorkFlowConfig_twoStepPCAndMDOApproval_success() throws Exception {

        prepareConfigMocks(Constants.TWO_STEP_PC_AND_MDO_APPROVAL, "/user-reg-path");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig(Constants.TWO_STEP_PC_AND_MDO_APPROVAL);

        assertNotNull(result);
    }

    @Test
    void testGetWorkFlowConfig_defaultCase() throws Exception {

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(buildMockWFConfig());
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(new HashMap<>());
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        WorkFlowModel result = service.getWorkFlowConfig("unknownService");

        assertNotNull(result);
    }

    @Test
    void testGetWorkFlowConfig_throwsException() {

        prepareConfigMocks(Constants.PROFILE_SERVICE_NAME, "/profile-path");

        when(requestServiceImpl.fetchResultUsingGet(any())).thenThrow(new RuntimeException("mock"));

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> service.getWorkFlowConfig(Constants.PROFILE_SERVICE_NAME));

        assertTrue(ex.getMessage().contains(Constants.WORKFLOW_PARSING_ERROR_MESSAGE));
    }

    @Test
    void testGetWfAction_success() throws Exception {
        WfStatus wfStatus = new WfStatus();
        WfAction wfAction = new WfAction();
        wfAction.setAction("approve");

        wfStatus.setActions(Arrays.asList(wfAction));

        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("getWfAction", String.class, WfStatus.class);
        method.setAccessible(true);

        WfAction result = (WfAction) method.invoke(service, "approve", wfStatus);

        assertNotNull(result);
        assertEquals("approve", result.getAction());
    }

    @Test
    void testGetWfAction_actionNotFound_shouldThrowException() throws Exception {
        WfStatus wfStatus = new WfStatus();
        WfAction wfAction = new WfAction();
        wfAction.setAction("reject");

        wfStatus.setActions(Arrays.asList(wfAction));

        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("getWfAction", String.class, WfStatus.class);
        method.setAccessible(true);

        InvocationTargetException exception = assertThrows(InvocationTargetException.class, () ->
                method.invoke(service, "approve", wfStatus)
        );

        assertNotNull(exception);
    }

    @Test
    void testGetWfStatus_success() throws Exception {
        // Arrange
        WfStatus status1 = new WfStatus();
        status1.setState("SUBMITTED");
        WfStatus status2 = new WfStatus();
        status2.setState("APPROVED");

        WorkFlowModel model = new WorkFlowModel();
        model.setWfstates(Arrays.asList(status1, status2));

        // Reflectively call private method
        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("getWfStatus", String.class, WorkFlowModel.class);
        method.setAccessible(true);

        // Act
        WfStatus result = (WfStatus) method.invoke(service, "APPROVED", model);

        // Assert
        assertNotNull(result);
        assertEquals("APPROVED", result.getState());
    }

    @Test
    void testGetWfStatus_stateNotFound_shouldThrowException() throws Exception {
        // Arrange
        WorkFlowModel model = new WorkFlowModel();
        model.setWfstates(Collections.emptyList());

        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("getWfStatus", String.class, WorkFlowModel.class);
        method.setAccessible(true);

        // Act & Assert
        Exception exception = assertThrows(InvocationTargetException.class, () ->
                method.invoke(service, "REJECTED", model)
        );

        // Unwrap InvocationTargetException
        Throwable cause = exception.getCause();
        assertTrue(cause instanceof BadRequestException);
        assertEquals(Constants.WORKFLOW_STATE_CHECK_ERROR, cause.getMessage());
    }

    private void prepareConfigMocks(String serviceName, String path) {
        when(configuration.getLmsServiceHost()).thenReturn("http://localhost");
        switch (serviceName) {
            case Constants.PROFILE_SERVICE_NAME:
                when(configuration.getProfileServiceConfigPath()).thenReturn(path);
                break;
            case Constants.USER_REGISTRATION_SERVICE_NAME:
                when(configuration.getUserRegistrationServiceConfigPath()).thenReturn(path);
                break;
            case Constants.POSITION_SERVICE_NAME:
                when(configuration.getPositionServiceConfigPath()).thenReturn(path);
                break;
            case Constants.ORGANISATION_SERVICE_NAME:
                when(configuration.getOrgServiceConfigPath()).thenReturn(path);
                break;
            case Constants.DOMAIN_SERVICE_NAME:
                when(configuration.getDomainServiceConfigPath()).thenReturn(path);
                break;
            case Constants.BLENDED_PROGRAM_SERVICE_NAME:
                when(configuration.getBlendedProgramServicePath()).thenReturn(path);
                break;
            case Constants.ONE_STEP_PC_APPROVAL:
                when(configuration.getMultilevelBPEnrolEndPoint()).thenReturn("/multi/");
                break;
            case Constants.ONE_STEP_MDO_APPROVAL:
                when(configuration.getMultilevelBPEnrolEndPoint()).thenReturn("/multi/");
                break;
            case Constants.TWO_STEP_MDO_AND_PC_APPROVAL:
                when(configuration.getMultilevelBPEnrolEndPoint()).thenReturn("/multi/");
                break;
            case Constants.TWO_STEP_PC_AND_MDO_APPROVAL:
                when(configuration.getMultilevelBPEnrolEndPoint()).thenReturn("/multi/");
                break;
            default:
                break;
        }
    }

    private Map<String, Object> buildMockWFConfig() {
        Map<String, Object> wfConfig = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> response = new HashMap<>();
        response.put(Constants.VALUE, "{}");
        result.put(Constants.RESPONSE, response);
        wfConfig.put(Constants.RESULT, result);
        return wfConfig;
    }
}
