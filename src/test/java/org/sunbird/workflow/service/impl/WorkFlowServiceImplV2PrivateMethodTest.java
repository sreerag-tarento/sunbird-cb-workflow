package org.sunbird.workflow.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.RedisCacheMgr;
import org.sunbird.workflow.exception.BadRequestException;
import org.sunbird.workflow.exception.InvalidDataInputException;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.models.WfStatus;
import org.sunbird.workflow.models.WorkFlowModel;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.producer.Producer;
import org.sunbird.workflow.utils.CassandraOperation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;

class WorkFlowServiceImplV2PrivateMethodTest {

    @InjectMocks
    private WorkFlowServiceImplV2 workFlowService;

    @Mock
    private Producer producer;

    @Mock
    private Configuration configuration;

    @Mock
    private WfStatusRepo wfStatusRepo;

    @Mock
    private RequestServiceImpl requestServiceImpl;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private CassandraOperation cassandraOperation;

    @Mock
    private RedisCacheMgr redisCacheMgr;

    WfStatusEntity applicationStatus;


    private static final String TO_VALUE = "toValue";
    @BeforeEach
    void setUp() {
        applicationStatus = new WfStatusEntity();
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testHandleProcessingError() throws Exception {
        // Arrange
        List<Object> data = List.of("item1", "item2");
        Response response = new Response();

        // Act
        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("handleProcessingError", List.class, Response.class);
        method.setAccessible(true);
        method.invoke(workFlowService, data, response);

        // Assert
        assertEquals("Failed to process workflow transition", response.get(Constants.MESSAGE));
        assertEquals(data, response.get(Constants.DATA));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.get(Constants.STATUS));
    }

    @Test
    void testHandleInvalidDataType() throws Exception {
        // Arrange
        Object invalidRequest = new Object();
        List<Object> data = List.of("item1", "item2");
        Response response = new Response();

        // Act
        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("handleInvalidDataType", Object.class, List.class, Response.class);
        method.setAccessible(true);
        method.invoke(workFlowService, invalidRequest, data, response);

        // Assert
        assertEquals("Invalid data type for workflow requests", response.get(Constants.MESSAGE));
        assertEquals(data, response.get(Constants.DATA));
        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
    }

    @Test
    void testPushWorkflowEvents_success() throws Exception {
        // Arrange
        String serviceName = "testService";
        String userId = "user123";
        List<Object> wfRequestsForEvent = List.of("req1", "req2");

        ReflectionTestUtils.setField(workFlowService, "producer", producer);
        ReflectionTestUtils.setField(workFlowService, "configuration", configuration);
        when(configuration.getWorkFlowNotificationTopicV2()).thenReturn("notificationTopic");
        when(configuration.getWorkflowApplicationTopicV2()).thenReturn("applicationTopic");

        String userToken = "test-auth-token";
        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("pushWorkflowEvents", String.class, String.class, List.class, String.class);
        method.setAccessible(true);

        // Act
        method.invoke(workFlowService, serviceName, userId, wfRequestsForEvent, userToken);

        // Assert
        Map<String, Object> expectedPayload = new HashMap<>();
        expectedPayload.put(Constants.USER_ID, userId);
        expectedPayload.put(Constants.SERVICE_NAME, serviceName);
        expectedPayload.put(Constants.WORKFLOW_REQUESTS, wfRequestsForEvent);
        expectedPayload.put(Constants.X_AUTH_TOKEN, userToken);

        verify(producer).push("notificationTopic", expectedPayload);
        verify(producer).push("applicationTopic", expectedPayload);
    }

    @Test
    void testPushWorkflowEvents_exception() throws Exception {
        // Arrange
        String serviceName = "testService";
        String userId = "user123";
        List<Object> wfRequestsForEvent = List.of("req1", "req2");

        ReflectionTestUtils.setField(workFlowService, "producer", producer);
        ReflectionTestUtils.setField(workFlowService, "configuration", configuration);
        when(configuration.getWorkFlowNotificationTopicV2()).thenReturn("notificationTopic");
        when(configuration.getWorkflowApplicationTopicV2()).thenReturn("applicationTopic");

        doThrow(new RuntimeException("Simulated error")).when(producer)
                .push(eq("notificationTopic"), any());

        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("pushWorkflowEvents", String.class, String.class, List.class, String.class);
        method.setAccessible(true);

        // Act
        method.invoke(workFlowService, serviceName, userId, wfRequestsForEvent, "test-auth-token");

        // Assert
        // verifies producer.push was attempted
        verify(producer).push(eq("notificationTopic"), any());
        // the second push may or may not happen depending if the first throws & is caught
        // no exception should propagate to the test
    }

    @Test
    void testHandleProfileServiceWorkflow_notExist() throws Exception {
        ReflectionTestUtils.setField(workFlowService, "wfStatusRepo", wfStatusRepo);
        ReflectionTestUtils.setField(workFlowService, "mapper", mapper);
        ReflectionTestUtils.setField(workFlowService, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(workFlowService, "configuration", configuration);
        ReflectionTestUtils.setField(workFlowService, "requestServiceImpl", requestServiceImpl);
        ReflectionTestUtils.setField(workFlowService, "producer", producer);
        ReflectionTestUtils.setField(workFlowService, "redisCacheMgr", redisCacheMgr);
        WfRequest wfRequest = createWfRequest();
        Map<String, Object> responseData = new HashMap<>();

        Method handleProfileServiceWorkflow = WorkFlowServiceImplV2.class
                .getDeclaredMethod("handleProfileServiceWorkflow", WfRequest.class, Map.class);
        handleProfileServiceWorkflow.setAccessible(true);

        WorkFlowServiceImplV2 spyService = spy(workFlowService);

        boolean result = (boolean) handleProfileServiceWorkflow.invoke(spyService, wfRequest, responseData);

        assertFalse(result);
    }

    @Test
    void testIsWFRequestExist_notFound() throws Exception {
        ReflectionTestUtils.setField(workFlowService, "wfStatusRepo", wfStatusRepo);
        ReflectionTestUtils.setField(workFlowService, "mapper", mapper);
        ReflectionTestUtils.setField(workFlowService, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(workFlowService, "configuration", configuration);
        ReflectionTestUtils.setField(workFlowService, "requestServiceImpl", requestServiceImpl);
        ReflectionTestUtils.setField(workFlowService, "producer", producer);
        ReflectionTestUtils.setField(workFlowService, "redisCacheMgr", redisCacheMgr);
        WfRequest wfRequest = createWfRequest();

        when(wfStatusRepo.getPendingRequests(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("isWFRequestExist", WfRequest.class);
        method.setAccessible(true);

        Map<String, Object> result = (Map<String, Object>) method.invoke(workFlowService, wfRequest);

        assertEquals(false, result.get(Constants.IS_WF_REQUEST_EXIST));
        assertEquals(wfRequest.getWfId(), result.get(Constants.WF_ID_CONSTANT));
    }

    @Test
    void testGetKeyFromUpdateFieldValues_valid() throws Exception {
        // Given
        String jsonInput = "[{\"" + TO_VALUE + "\":{\"key\":\"value\"}}]";

        Map<String, Object> toValueMap = Map.of("key", "value");
        Map<String, Object> item = Map.of(TO_VALUE, toValueMap);
        List<Map<String, Object>> list = List.of(item);

        when(mapper.readValue(eq(jsonInput), any(TypeReference.class)))
                .thenReturn(list);

        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("getKeyFromUpdateFieldValues", String.class);
        method.setAccessible(true);

        // When
        String result = (String) method.invoke(workFlowService, jsonInput);

        // Then
        assertEquals("key", result);
    }

    @Test
    void testGetKeyFromUpdateFieldValues_emptyToValue() throws Exception {
        // Given
        String jsonInput = "[{\"" + TO_VALUE + "\":{}}]";

        Map<String, Object> toValueMap = Map.of();  // empty
        Map<String, Object> item = Map.of(TO_VALUE, toValueMap);
        List<Map<String, Object>> list = List.of(item);

        when(mapper.readValue(eq(jsonInput), any(TypeReference.class)))
                .thenReturn(list);

        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("getKeyFromUpdateFieldValues", String.class);
        method.setAccessible(true);

        // When
        String result = (String) method.invoke(workFlowService, jsonInput);

        // Then
        assertNull(result);
    }

    @Test
    void testGetKeyFromUpdateFieldValues_mapperThrowsException() throws Exception {
        // Given
        String jsonInput = "invalid_json";

        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("getKeyFromUpdateFieldValues", String.class);
        method.setAccessible(true);

        // When & Then
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> {
            method.invoke(workFlowService, jsonInput);
        });
        assertTrue(thrown.getCause() instanceof Exception);
    }

    @ParameterizedTest
    @MethodSource("requestTypeProvider")
    void testAddRequestTypeParameterized(String inputKey, String expectedRequestType) throws Exception {
        WfRequest wfRequest = createWfRequestWithKey(inputKey);

        Method method = WorkFlowServiceImplV2.class
                .getDeclaredMethod("addRequestTypeInProfileWF", WfRequest.class);
        method.setAccessible(true);

        method.invoke(workFlowService, wfRequest);

        assertEquals(expectedRequestType, wfRequest.getRequestType());
    }

    private static Stream<Arguments> requestTypeProvider() {
        return Stream.of(
                Arguments.of("group", "GROUP_CHANGE"),
                Arguments.of("designation", "DESIGNATION_CHANGE"),
                Arguments.of("name", "ORG_TRANSFER"),
                Arguments.of("otherKey", "otherKey")
        );
    }

    private WfRequest createWfRequestWithKey(String key) {
        WfRequest request = new WfRequest();
        Map<String, Object> toValue = new HashMap<>();
        toValue.put(key, "dummyValue");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);

        request.setUpdateFieldValues(updateFieldValues);

        return request;
    }

    @Test
    void testResolveServiceName_blendedProgramWithApplicationStatus() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);

        applicationStatus = new WfStatusEntity();
        applicationStatus.setServiceName("fromApplicationStatus");

        String result = ReflectionTestUtils.invokeMethod(workFlowService, "resolveServiceName", wfRequest, applicationStatus);

        assertEquals("fromApplicationStatus", result);
    }

    @Test
    void testResolveServiceName_blendedProgramWithoutApplicationStatus() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);

        applicationStatus = new WfStatusEntity(); // serviceName is null

        String result = ReflectionTestUtils.invokeMethod(workFlowService, "resolveServiceName", wfRequest, applicationStatus);

        assertEquals(Constants.BLENDED_PROGRAM_SERVICE_NAME, result);
    }

    @Test
    void testResolveServiceName_nonBlendedProgram() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setServiceName("SOME_OTHER_SERVICE");

        applicationStatus = new WfStatusEntity();
        applicationStatus.setServiceName("fromApplicationStatus");

        String result = ReflectionTestUtils.invokeMethod(workFlowService, "resolveServiceName", wfRequest, applicationStatus);

        assertEquals("SOME_OTHER_SERVICE", result);
    }

    @Test
    void testResolveServiceName_blendedProgramWithNullApplicationStatus() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);

        String result = ReflectionTestUtils.invokeMethod(workFlowService, "resolveServiceName", wfRequest, null);

        assertEquals(Constants.BLENDED_PROGRAM_SERVICE_NAME, result);
    }

    @Test
    void testInitializeApplicationStatus() {
        String rootOrg = "rootOrg";
        String org = "org";
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("userId");
        wfRequest.setApplicationId("appId");
        wfRequest.setRequestType("requestType");

        String serviceName = "serviceName";

        applicationStatus = ReflectionTestUtils.invokeMethod(
                workFlowService, "initializeApplicationStatus", rootOrg, org, wfRequest, serviceName);

        assertNotNull(applicationStatus);
        assertNotNull(applicationStatus.getWfId());
        assertEquals(serviceName, applicationStatus.getServiceName());
        assertEquals("userId", applicationStatus.getUserId());
        assertEquals("appId", applicationStatus.getApplicationId());
        assertEquals(rootOrg, applicationStatus.getRootOrg());
        assertEquals(org, applicationStatus.getOrg());
        assertNotNull(applicationStatus.getCreatedOn());
        assertEquals("requestType", applicationStatus.getRequestType());
        assertEquals(applicationStatus.getWfId(), wfRequest.getWfId());
    }

    @Test
    void testHandleSpecialFields_withNameKey() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user123");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put(Constants.NAME, "newName");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);


        wfRequest.setUpdateFieldValues(updateFieldValues);

        Map<String, Object> userRecord = Map.of(Constants.USER_ROOT_ORG_ID, "rootOrgId");
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any()))
                .thenReturn(List.of(userRecord));

        ReflectionTestUtils.invokeMethod(workFlowService, "handleSpecialFields", wfRequest);

        assertEquals("rootOrgId", wfRequest.getPreviousRootOrgId());
    }

    @Test
    void testHandleSpecialFields_withoutNameKey() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user123");
        Map<String, Object> toValue = new HashMap<>();
        toValue.put("notName", "value");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        wfRequest.setUpdateFieldValues(updateFieldValues);

        ReflectionTestUtils.invokeMethod(workFlowService, "handleSpecialFields", wfRequest);

        assertNull(wfRequest.getPreviousRootOrgId());
        verifyNoInteractions(cassandraOperation);
    }

    @Test
    void testHandleSpecialFields_emptyUpdateFieldValues() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user123");
        wfRequest.setUpdateFieldValues(Collections.emptyList());

        ReflectionTestUtils.invokeMethod(workFlowService, "handleSpecialFields", wfRequest);

        assertNull(wfRequest.getPreviousRootOrgId());
    }

    @Test
    void testValidateWfRequest_valid() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setState("state");
        wfRequest.setApplicationId("appId");
        wfRequest.setActorUserId("actorId");
        wfRequest.setUserId("userId");
        wfRequest.setAction("someAction");
        wfRequest.setServiceName("serviceName");
        wfRequest.setUpdateFieldValues(List.of(new java.util.HashMap<>()));

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest)
        );
    }

    @Test
    void testValidateWfRequest_missingState() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setState(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.STATE_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_missingApplicationId() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setApplicationId("");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.APPLICATION_ID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_missingActorUserId() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setActorUserId(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.ACTOR_UUID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_missingUserId() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setUserId("");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.USER_UUID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_missingAction() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setAction(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.ACTION_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_missingUpdateFieldValues_whenNotWithdraw() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setUpdateFieldValues(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.FIELD_VALUE_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_missingServiceName() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setServiceName("");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class,
                () -> ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest));
        assertEquals(Constants.WORKFLOW_SERVICENAME_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_withdrawAction_allowsEmptyUpdateFieldValues() {
        WfRequest wfRequest = createValidWfRequest();
        wfRequest.setAction(Constants.WITHDRAW);
        wfRequest.setUpdateFieldValues(null);

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workFlowService, "validateWfRequest", wfRequest)
        );
    }


    @Test
    void testValidateUserAndWfStatus_invalidStateMismatch() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId("wf123");
        wfRequest.setState("APPROVED");

        WfStatus wfStatus = new WfStatus();
        wfStatus.setStartState(true);

        applicationStatus = new WfStatusEntity();
        applicationStatus.setCurrentStatus("REJECTED");

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                ReflectionTestUtils.invokeMethod(workFlowService,
                        "validateUserAndWfStatus", wfRequest, wfStatus, applicationStatus)
        );
        assertTrue(ex.getMessage().contains("Application is in"));
    }

    @Test
    void testValidateUserAndWfStatus_validApplicationStatusNull() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId("wf123");
        wfRequest.setState("APPROVED");

        WfStatus wfStatus = new WfStatus();
        wfStatus.setStartState(true);

        applicationStatus = null;

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workFlowService,
                        "validateUserAndWfStatus", wfRequest, wfStatus, applicationStatus)
        );
    }

    @Test
    void testValidateUserAndWfStatus_validMatchingState() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId("wf123");
        wfRequest.setState("APPROVED");

        WfStatus wfStatus = new WfStatus();
        wfStatus.setStartState(true);

        applicationStatus = new WfStatusEntity();
        applicationStatus.setCurrentStatus("APPROVED");

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workFlowService,
                        "validateUserAndWfStatus", wfRequest, wfStatus, applicationStatus)
        );
    }

    @Test
    void testAddModificationEntry_withExistingHistory() throws Exception {
        String userId = "user1";
        String action = "APPROVE";
        String role = "ROLE_ADMIN";
        String existingHistoryJson = "[{\"modifiedBy\":\"user0\",\"action\":\"SUBMIT\"}]";

        applicationStatus.setModificationHistory(existingHistoryJson);

        when(configuration.getModificationRecordAllowActions()).thenReturn("APPROVE,REJECT");
        when(mapper.readValue(existingHistoryJson, List.class))
                .thenReturn(new ArrayList<>(List.of(new HashMap<>(Map.of("modifiedBy", "user0", "action", "SUBMIT")))));

        when(mapper.writeValueAsString(any())).thenReturn("updatedHistory");

        // invoke private method
        ReflectionTestUtils.invokeMethod(workFlowService,
                "addModificationEntry", applicationStatus, userId, action, role);

        assertEquals("updatedHistory", applicationStatus.getModificationHistory());

        verify(mapper).readValue(existingHistoryJson, List.class);
        verify(mapper).writeValueAsString(any());
    }

    @Test
    void testAddModificationEntry_noExistingHistory() throws Exception {
        String userId = "user1";
        String action = "APPROVE";
        String role = "ROLE_ADMIN";

        applicationStatus.setModificationHistory(null);

        when(configuration.getModificationRecordAllowActions()).thenReturn("APPROVE,REJECT");
        when(mapper.writeValueAsString(any())).thenReturn("newHistory");

        // invoke private method
        ReflectionTestUtils.invokeMethod(workFlowService,
                "addModificationEntry", applicationStatus, userId, action, role);

        assertEquals("newHistory", applicationStatus.getModificationHistory());

        verify(mapper, never()).readValue(anyString(), eq(List.class));
        verify(mapper).writeValueAsString(any());
    }

    @Test
    void testAddModificationEntry_actionNotAllowed() {
        String userId = "user1";
        String action = "INVALID_ACTION";
        String role = "ROLE_ADMIN";

        applicationStatus.setModificationHistory(null);

        when(configuration.getModificationRecordAllowActions()).thenReturn("APPROVE,REJECT");

        // invoke private method
        ReflectionTestUtils.invokeMethod(workFlowService,
                "addModificationEntry", applicationStatus, userId, action, role);

        assertNull(applicationStatus.getModificationHistory());
        verifyNoInteractions(mapper);
    }

    @Test
    void testAddModificationEntry_userIdEmpty() {
        String userId = "";
        String action = "APPROVE";
        String role = "ROLE_ADMIN";

        applicationStatus.setModificationHistory(null);

        // invoke private method
        ReflectionTestUtils.invokeMethod(workFlowService,
                "addModificationEntry", applicationStatus, userId, action, role);

        assertNull(applicationStatus.getModificationHistory());
        verifyNoInteractions(mapper);
    }

    @Test
    void testUpdateApplicationStatus_orgTransferRequest() throws Exception {
        // Arrange
        applicationStatus = new WfStatusEntity();
        applicationStatus.setRequestType("ORG_TRANSFER");
        applicationStatus.setUserId("user-1");

        WfRequest wfRequest = new WfRequest();
        wfRequest.setActorUserId("actor-1");
        wfRequest.setDeptName("IT");
        wfRequest.setComment("Approved");
        wfRequest.setAction("APPROVE");
        wfRequest.setServiceName("PROFILE_SERVICE");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("name", "Finance");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        wfRequest.setUpdateFieldValues(updateFieldValues);

        String nextState = "APPROVED";
        String userId = "user-1";
        String role = "manager";

        WfStatus wfStatus = new WfStatus();
        wfStatus.setState(nextState);
        wfStatus.setIsLastState(true);  // simulate last state

        WorkFlowModel workFlowModel = new WorkFlowModel();
        workFlowModel.setWfstates(List.of(wfStatus));

        when(mapper.writeValueAsString(any())).thenReturn("[{\"toValue\":{\"name\":\"Finance\"}}]");

        WfStatusEntity savedEntity = new WfStatusEntity();
        savedEntity.setUserId("user-1");
        savedEntity.setWfId("wf-123");
        savedEntity.setRequestType("ORG_TRANSFER_REQUEST");
        savedEntity.setCurrentStatus(nextState);
        when(wfStatusRepo.save(any())).thenReturn(savedEntity);

        WfStatusEntity entity1 = new WfStatusEntity();
        entity1.setUserId("user-1");
        entity1.setWfId("wf-123");
        entity1.setUpdateFieldValues("[{\"toValue\":{\"group\":\"HR\"}}]"); // ✅ ensure non-null valid JSON
        List<WfStatusEntity> transferList = List.of(entity1);
        when(wfStatusRepo.findByUserIdAndCurrentStatus("user-1", "SEND_FOR_APPROVAL", true))
                .thenReturn(transferList);

        when(configuration.getTransferRequestStatusChangeTopic()).thenReturn("transfer.topic");
        when(configuration.getModificationRecordAllowActions()).thenReturn("APPROVE,REJECT");

        // Act
        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("updateApplicationStatus",
                WfStatusEntity.class, WfRequest.class, String.class, String.class, String.class, WorkFlowModel.class, String.class);
        method.setAccessible(true);

        method.invoke(workFlowService, applicationStatus, wfRequest, nextState, userId, role, workFlowModel, "test-auth-token");

        // Assert
        verify(wfStatusRepo, times(1)).save(any());
    }

    @Test
    void testUpdateApplicationStatus_wfStatusNotFound_shouldThrowException() throws Exception {
        // Arrange
        applicationStatus = new WfStatusEntity();
        WfRequest wfRequest = new WfRequest();
        wfRequest.setActorUserId("actor");
        wfRequest.setUpdateFieldValues(List.of());

        String nextState = "UNKNOWN";
        String userId = "user-1";
        String role = "manager";

        WorkFlowModel workFlowModel = new WorkFlowModel(); // empty states list
        workFlowModel.setWfstates(Collections.emptyList());

        // Act
        Method method = WorkFlowServiceImplV2.class.getDeclaredMethod("updateApplicationStatus",
                WfStatusEntity.class, WfRequest.class, String.class, String.class, String.class, WorkFlowModel.class, String.class);
        method.setAccessible(true);

        // Assert
        Exception exception = assertThrows(InvocationTargetException.class, () -> {
            method.invoke(workFlowService, applicationStatus, wfRequest, nextState, userId, role, workFlowModel, "test-auth-token");
        });

        assertTrue(exception.getCause() instanceof BadRequestException);
        assertEquals(Constants.WORKFLOW_STATE_CHECK_ERROR, exception.getCause().getMessage());
    }


    private WfRequest createValidWfRequest() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setState("state");
        wfRequest.setApplicationId("appId");
        wfRequest.setActorUserId("actorId");
        wfRequest.setUserId("userId");
        wfRequest.setAction("action");
        wfRequest.setServiceName("serviceName");
        wfRequest.setUpdateFieldValues(List.of(new java.util.HashMap<>()));
        return wfRequest;
    }

    private WfRequest createWfRequest() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("appId");
        wfRequest.setWfId("wfId456");
        wfRequest.setServiceName(Constants.PROFILE_SERVICE_NAME);
        wfRequest.setUserId("user123");

        HashMap<String, Object> fromValue = new HashMap<>();
        fromValue.put("name", "oldDept");

        HashMap<String, Object> toValue = new HashMap<>();
        toValue.put("name", "newDept");

        HashMap<String, Object> updateMap = new HashMap<>();
        updateMap.put("fieldKey", "professionalDetails");
        updateMap.put(Constants.FROM_VALUE, fromValue);
        updateMap.put(Constants.TO_VALUE, toValue);

        wfRequest.setUpdateFieldValues(List.of(updateMap));

        return wfRequest;
    }

}
