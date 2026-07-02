package org.sunbird.workflow.service.impl;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.exception.ApplicationException;
import org.sunbird.workflow.exception.BadRequestException;
import org.sunbird.workflow.exception.InvalidDataInputException;
import org.sunbird.workflow.models.*;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.utils.NotificationTriggerService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplPrivateMethodTest {

    @InjectMocks
    WorkflowServiceImpl workflowService;

    @Mock
    Configuration configuration;

    @Mock
    ObjectMapper mapper;

    WfStatusEntity applicationStatus;

    @Mock
    RequestServiceImpl requestServiceImpl;

    @Mock
    WfStatusRepo wfStatusRepo;

    @Mock
    NotificationTriggerService notificationTriggerService;

    @BeforeEach
    void setup() {
        applicationStatus = new WfStatusEntity();
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
        ReflectionTestUtils.invokeMethod(workflowService,
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
        ReflectionTestUtils.invokeMethod(workflowService,
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
        ReflectionTestUtils.invokeMethod(workflowService,
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
        ReflectionTestUtils.invokeMethod(workflowService,
                "addModificationEntry", applicationStatus, userId, action, role);

        assertNull(applicationStatus.getModificationHistory());
        verifyNoInteractions(mapper);
    }
    @Test
    void testValidateUserAndWfStatus_missingWfIdAndNotStartState() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId(null);

        WfStatus wfStatus = new WfStatus();
        wfStatus.setStartState(false);

        applicationStatus = null;

        ApplicationException ex = assertThrows(ApplicationException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService,
                        "validateUserAndWfStatus", wfRequest, wfStatus, applicationStatus)
        );
        assertEquals("Workflow id can not be empty!", ex.getMessage());
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
                ReflectionTestUtils.invokeMethod(workflowService,
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
                ReflectionTestUtils.invokeMethod(workflowService,
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

        applicationStatus.setCurrentStatus("APPROVED");

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workflowService,
                        "validateUserAndWfStatus", wfRequest, wfStatus, applicationStatus)
        );
    }


    @Test
    void testGetNextApplicableActions_emptyList() throws Exception {
        when(mapper.writeValueAsString(null)).thenReturn("[]");

        String result = ReflectionTestUtils.invokeMethod(
                workflowService,
                "getNextApplicableActions",
                (Object) null
        );

        assertEquals("[]", result);
        verify(mapper).writeValueAsString(null);
    }

    @Test
    void testGetNextApplicableActions_withActions() throws Exception {
        List<WfAction> actions = new ArrayList<>();
        WfAction action = new WfAction();
        action.setAction("APPROVE");
        action.setRoles(List.of("role1", "role2"));
        actions.add(action);

        when(mapper.writeValueAsString(any())).thenReturn("[{\"action\":\"APPROVE\",\"roles\":[\"role1\",\"role2\"]}]");

        String result = ReflectionTestUtils.invokeMethod(
                workflowService,
                "getNextApplicableActions",
                actions
        );

        assertNotNull(result);
        assertTrue(result.contains("APPROVE"));
        verify(mapper).writeValueAsString(any());
    }

    @Test
    void testValidateRoles_bothListsEmpty() {
        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateRoles", null, null)
        );

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateRoles", List.of(), List.of())
        );

        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateRoles", List.of(""), List.of())
        );
    }

    @Test
    void testValidateRoles_actorRolesEmpty_throwsApplicationException() {
        List<String> actorRoles = List.of();
        List<String> allowedRoles = List.of("role1");

        Executable executable = () -> ReflectionTestUtils.invokeMethod(workflowService, "validateRoles", actorRoles, allowedRoles);

        ApplicationException ex = assertThrows(ApplicationException.class, executable);
        assertEquals(Constants.WORKFLOW_ROLE_ERROR, ex.getMessage());
    }


    @Test
    void testValidateRoles_rolesDoNotMatch_throwsBadRequestException() {
        List<String> actors = List.of("actor1");
        List<String> roles = List.of("role1", "role2");

        Executable executable = () -> ReflectionTestUtils.invokeMethod(workflowService, "validateRoles", actors, roles);

        BadRequestException ex = assertThrows(BadRequestException.class, executable);
        assertEquals(Constants.WORKFLOW_ROLE_CHECK_ERROR, ex.getMessage());
    }


    @Test
    void testValidateRoles_rolesMatch_ok() {
        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateRoles",
                        List.of("role1", "actor2"), List.of("role1", "role2"))
        );
    }

    @Test
    void testGetWfAction_whenActionsIsNull_shouldThrow() {
        WfStatus wfStatus = new WfStatus();
        wfStatus.setActions(null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "getWfAction", "APPROVE", wfStatus)
        );

        assertEquals(Constants.WORKFLOW_ACTION_ERROR, ex.getMessage());
    }

    @Test
    void testGetWfAction_whenNoMatchFound_shouldThrow() {
        WfAction action1 = new WfAction();
        action1.setAction("REJECT");

        WfStatus wfStatus = new WfStatus();
        wfStatus.setActions(List.of(action1));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "getWfAction", "APPROVE", wfStatus)
        );

        assertEquals(Constants.WORKFLOW_ACTION_ERROR, ex.getMessage());
    }

    @Test
    void testGetWfAction_whenMatchFound_shouldReturnWfAction() {
        WfAction action1 = new WfAction();
        action1.setAction("APPROVE");
        action1.setRoles(List.of("ROLE_USER"));

        WfStatus wfStatus = new WfStatus();
        wfStatus.setActions(List.of(action1));

        WfAction result = ReflectionTestUtils.invokeMethod(workflowService, "getWfAction", "APPROVE", wfStatus);

        assertNotNull(result);
        assertEquals("APPROVE", result.getAction());
        assertEquals(List.of("ROLE_USER"), result.getRoles());
    }

    @Test
    void testGetWfStatus_whenStateNotFound_shouldThrow() {
        WorkFlowModel workFlowModel = new WorkFlowModel();
        WfStatus status1 = new WfStatus();
        status1.setState("APPROVED");
        workFlowModel.setWfstates(List.of(status1));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "getWfStatus", "REJECTED", workFlowModel)
        );

        assertEquals(Constants.WORKFLOW_STATE_CHECK_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_stateMissing() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("app");
        wfRequest.setActorUserId("actor");
        wfRequest.setUserId("user");
        wfRequest.setAction("action");
        wfRequest.setUpdateFieldValues(List.of());
        wfRequest.setServiceName("service");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals(Constants.STATE_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_applicationIdMissing() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setApplicationId(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals(Constants.APPLICATION_ID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_actorUserIdMissing() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setActorUserId("");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals(Constants.ACTOR_UUID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_userIdMissing() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setUserId(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals(Constants.USER_UUID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_actionMissing() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setAction("");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals(Constants.ACTION_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_updateFieldValuesMissing() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setUpdateFieldValues(Collections.emptyList());

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals(Constants.FIELD_VALUE_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequest_serviceNameMissing() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setServiceName("");

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
        assertEquals("Update Field values can not be empty!", ex.getMessage());
    }

    @Test
    void testValidateWfRequest_withdrawAction_allowsEmptyUpdateFieldValues() {
        WfRequest wfRequest = buildValidRequest();
        wfRequest.setAction(Constants.WITHDRAW);
        wfRequest.setUpdateFieldValues(Collections.emptyList());

        // No exception expected
        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(workflowService, "validateWfRequest", wfRequest)
        );
    }

    @Test
    void testGetUserRoles_bothRolesPresent() {
        String userId = "user123";

        when(configuration.getLexCoreServiceHost()).thenReturn("http://lex/");
        when(configuration.getUserRoleSearchEndpoint()).thenReturn("/roles/{user_id}");

        Map<String, Object> response = new HashMap<>();
        response.put("default_roles", List.of("default1", "default2"));
        response.put("user_roles", List.of("role1", "role2"));

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(response);

        List<String> result = ReflectionTestUtils.invokeMethod(workflowService, "getUserRoles", userId);

        assertEquals(4, result.size());
        assertEquals(Set.of("default1", "default2", "role1", "role2"), new HashSet<>(result));
    }

    @Test
    void testGetUserRoles_onlyDefaultRoles() {
        String userId = "user123";

        when(configuration.getLexCoreServiceHost()).thenReturn("http://lex/");
        when(configuration.getUserRoleSearchEndpoint()).thenReturn("/roles/{user_id}");

        Map<String, Object> response = new HashMap<>();
        response.put("default_roles", List.of("default1"));
        response.put("user_roles", null);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(response);

        List<String> result = ReflectionTestUtils.invokeMethod(workflowService, "getUserRoles", userId);

        assertEquals(1, result.size());
        assertEquals(List.of("default1"), result);
    }

    @Test
    void testGetUserRoles_onlyUserRoles() {
        String userId = "user123";

        when(configuration.getLexCoreServiceHost()).thenReturn("http://lex/");
        when(configuration.getUserRoleSearchEndpoint()).thenReturn("/roles/{user_id}");

        Map<String, Object> response = new HashMap<>();
        response.put("default_roles", null);
        response.put("user_roles", List.of("role1"));

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(response);

        List<String> result = ReflectionTestUtils.invokeMethod(workflowService, "getUserRoles", userId);

        assertEquals(1, result.size());
        assertEquals(List.of("role1"), result);
    }

    @Test
    void testGetUserRoles_bothEmpty() {
        String userId = "user123";

        when(configuration.getLexCoreServiceHost()).thenReturn("http://lex/");
        when(configuration.getUserRoleSearchEndpoint()).thenReturn("/roles/{user_id}");

        Map<String, Object> response = new HashMap<>();
        response.put("default_roles", null);
        response.put("user_roles", null);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(response);

        List<String> result = ReflectionTestUtils.invokeMethod(workflowService, "getUserRoles", userId);

        assertEquals(0, result.size());
    }

    @Test
    void testPopulateSheetWithPendingRequests() throws Exception {
        // Given
        Map<String, Object> allUserDetails = new HashMap<>();
        Map<String, Object> allPendingRequestMap = new HashMap<>();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("firstName", "John");
        userInfo.put("primaryEmail", "john@example.com");
        userInfo.put("mobileNumber", "1234567890");

        List<Map<String, Object>> pendingRequests = new ArrayList<>();
        Map<String, Object> pendingRequest = new HashMap<>();
        pendingRequest.put("GROUP", "Engineering");
        pendingRequest.put("DESIGNATION", "Developer");
        pendingRequests.add(pendingRequest);

        String userId = "user123";
        allUserDetails.put(userId, userInfo);
        allPendingRequestMap.put(userId, pendingRequests);

        File tempFile = Files.createTempFile("test", ".csv").toFile();
        tempFile.deleteOnExit();

        // When
        ReflectionTestUtils.invokeMethod(workflowService,
                "populateSheetWithPendingRequests",
                allPendingRequestMap,
                allUserDetails,
                tempFile.getAbsolutePath());

        // Then
        List<String> lines = Files.readAllLines(tempFile.toPath());
        assertFalse(lines.isEmpty());
        assertEquals("Full Name,Email,Mobile Number,Group,Designation,Gender,Category,Date of Birth (dd-mm-yyy),Mother Tongue,Employee ID,Office Pin Code,External System ID,External System Name,Tags",
                lines.get(0));

        String dataLine = lines.get(1);
        assertTrue(dataLine.contains("john@example.com"));
        assertTrue(dataLine.contains("1234567890"));

        // Cleanup
        tempFile.delete();
    }

    @Test
    void testPopulateSheetWithNoGroupOrDesignation() throws Exception {
        // Given
        Map<String, Object> allUserDetails = new HashMap<>();
        Map<String, Object> allPendingRequestMap = new HashMap<>();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("firstName", "Alice");
        userInfo.put("primaryEmail", "alice@example.com");
        userInfo.put("mobileNumber", null);

        List<Map<String, Object>> pendingRequests = new ArrayList<>();
        Map<String, Object> pendingRequest = new HashMap<>(); // no group/designation keys
        pendingRequests.add(pendingRequest);

        String userId = "user456";
        allUserDetails.put(userId, userInfo);
        allPendingRequestMap.put(userId, pendingRequests);

        File tempFile = Files.createTempFile("test2", ".csv").toFile();
        tempFile.deleteOnExit();

        // When
        ReflectionTestUtils.invokeMethod(workflowService,
                "populateSheetWithPendingRequests",
                allPendingRequestMap,
                allUserDetails,
                tempFile.getAbsolutePath());

        // Then
        List<String> lines = Files.readAllLines(tempFile.toPath());
        assertFalse(lines.isEmpty());
        assertEquals(2, lines.size());
        String dataLine = lines.get(1);

        assertTrue(dataLine.contains("alice@example.com"));
        assertTrue(dataLine.contains(",,,")); // mobile, group, designation empty

        // Cleanup
        tempFile.delete();
    }

    @Test
    void testPopulateSheetWithPrimaryEmail() throws Exception {
        // Given
        Map<String, Object> allUserDetails = new HashMap<>();
        Map<String, Object> allPendingRequestMap = new HashMap<>();

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("firstName", "Bob");
        userInfo.put("primaryEmail", "bob@example.com");
        userInfo.put("mobileNumber", "9999999999");

        List<Map<String, Object>> pendingRequests = new ArrayList<>();
        Map<String, Object> pendingRequest = new HashMap<>();
        pendingRequest.put("GROUP", "Support");
        pendingRequest.put("DESIGNATION", "Agent");
        pendingRequests.add(pendingRequest);

        String userId = "user789";
        allUserDetails.put(userId, userInfo);
        allPendingRequestMap.put(userId, pendingRequests);

        File tempFile = Files.createTempFile("test3", ".csv").toFile();
        tempFile.deleteOnExit();

        // When
        ReflectionTestUtils.invokeMethod(workflowService,
                "populateSheetWithPendingRequests",
                allPendingRequestMap,
                allUserDetails,
                tempFile.getAbsolutePath());

        // Then
        List<String> lines = Files.readAllLines(tempFile.toPath());
        assertFalse(lines.isEmpty());
        assertEquals(2, lines.size());
        String dataLine = lines.get(1);

        // Explicitly test that PRIMARY_EMAIL is populated in the CSV
        assertTrue(dataLine.contains("bob@example.com"),
                "CSV should contain the primary email `bob@example.com`");

        // Cleanup
        tempFile.delete();
    }

    @Test
    void testPreparePendingRequestFileResponse() throws Exception {
        // Arrange: Create dummy file
        String csvContent = "header1,header2\nval1,val2";
        String tempDir = System.getProperty("java.io.tmpdir");
        String fileName = "pendingRequest.csv";
        String csvFilePath = tempDir + File.separator + fileName;
        Path localBasePath = Paths.get(Constants.LOCAL_BASE_PATH);
        Files.createDirectories(localBasePath); // ensure base path exists
        Path targetPath = localBasePath.resolve(fileName);

        Files.writeString(Paths.get(csvFilePath), csvContent);
        Files.copy(Paths.get(csvFilePath), targetPath, StandardCopyOption.REPLACE_EXISTING);

        int size = 42;

        // Act: invoke private method
        ResponseEntity<?> response = (ResponseEntity<?>) ReflectionTestUtils.invokeMethod(
                workflowService,
                "preparePendingRequestFileResponse",
                csvFilePath,
                size);

        // Assert: check response
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());

        org.springframework.http.HttpHeaders headers = response.getHeaders();
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, headers.getContentType());
        assertTrue(headers.containsKey(Constants.COUNT));
        assertEquals(String.valueOf(size), headers.get(Constants.COUNT).get(0));

        Object body = response.getBody();
        assertTrue(body instanceof InputStreamResource);

        // file should be deleted after call
        assertFalse(Files.exists(targetPath), "File should have been deleted");
    }

    @Test
    void testIsWFRequestExist_whenRequestExists() throws Exception {
        // Arrange
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");
        wfRequest.setServiceName("service1");
        wfRequest.setWfId("wf1");
        HashMap<String, Object> toValue = new HashMap();
        toValue.put("key1", "val1");
        HashMap<String, Object> updateMap = new HashMap<>();
        updateMap.put(Constants.TO_VALUE, toValue);
        wfRequest.setUpdateFieldValues(List.of(updateMap));

        WfStatusEntity entity = new WfStatusEntity();
        entity.setWfId("existingWfId");
        entity.setUpdateFieldValues("[{\"toValue\":{\"key1\":\"val2\"}}]");
        when(wfStatusRepo.getPendingRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(entity));
        when(mapper.readValue(eq(entity.getUpdateFieldValues()), any(TypeReference.class)))
                .thenReturn(List.of(Map.of(Constants.TO_VALUE, Map.of("key1", "val2"))));

        // Act
        Map<String, Object> result = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                workflowService, "isWFRequestExist", wfRequest);

        // Assert
        assertTrue((Boolean) result.get(Constants.IS_WF_REQUEST_EXIST));
        assertEquals("existingWfId", result.get(Constants.WF_ID_CONSTANT));
    }

    @Test
    void testIsWFRequestExist_whenRequestDoesNotExist() throws Exception {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");
        wfRequest.setServiceName("service1");
        wfRequest.setWfId("wf1");
        HashMap<String, Object> toValue = new HashMap();
        toValue.put("key1", "val1");
        HashMap<String, Object> updateMap = new HashMap<>();
        updateMap.put(Constants.TO_VALUE, toValue);
        wfRequest.setUpdateFieldValues(List.of(updateMap));

        WfStatusEntity entity = new WfStatusEntity();
        entity.setWfId("existingWfId");
        entity.setUpdateFieldValues("[{\"toValue\":{\"key2\":\"val2\"}}]");
        when(wfStatusRepo.getPendingRequests(anyString(), anyString(), anyString()))
                .thenReturn(List.of(entity));
        when(mapper.readValue(eq(entity.getUpdateFieldValues()), any(TypeReference.class)))
                .thenReturn(List.of(Map.of(Constants.TO_VALUE, Map.of("key2", "val2"))));

        Map<String, Object> result = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                workflowService, "isWFRequestExist", wfRequest);

        assertFalse((Boolean) result.get(Constants.IS_WF_REQUEST_EXIST));
        assertEquals("wf1", result.get(Constants.WF_ID_CONSTANT));
    }

    @Test
    void testIsWFRequestExist_invalidRequest() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ReflectionTestUtils.invokeMethod(workflowService, "isWFRequestExist", (WfRequest) null));
        assertEquals("Invalid WfRequest or updateFieldValues", ex.getMessage());
    }

    @Test
    void testGetKeyFromUpdateFieldValues_happyPath() throws Exception {
        String json = "[{\"toValue\":{\"key1\":\"val1\"}}]";
        when(mapper.readValue(eq(json), any(TypeReference.class)))
                .thenReturn(List.of(Map.of(Constants.TO_VALUE, Map.of("key1", "val1"))));

        String result = (String) ReflectionTestUtils.invokeMethod(
                workflowService, "getKeyFromUpdateFieldValues", json);

        assertEquals("key1", result);
    }

    @Test
    void testGetKeyFromUpdateFieldValues_emptyToValue() throws Exception {
        String json = "[{\"toValue\":{}}]";
        when(mapper.readValue(eq(json), any(TypeReference.class)))
                .thenReturn(List.of(Map.of(Constants.TO_VALUE, Map.of())));

        String result = (String) ReflectionTestUtils.invokeMethod(
                workflowService, "getKeyFromUpdateFieldValues", json);

        assertNull(result);
    }

@Test
void testHandleProfileServiceWorkflow_requestExists() {
    WfRequest wfRequest = buildWfRequest("group");
    Response response = new Response();
    Map<String, Object> data = new HashMap<>();


    boolean result = (boolean) ReflectionTestUtils.invokeMethod(
            workflowService, "handleProfileServiceWorkflow", wfRequest, response, data);

    assertFalse(result);
}

    @Test
    void testHandleProfileServiceWorkflow_requestDoesNotExist() {
        WfRequest wfRequest = buildWfRequest("designation");
        Response response = new Response();
        Map<String, Object> data = new HashMap<>();

        boolean result = (boolean) ReflectionTestUtils.invokeMethod(
                workflowService, "handleProfileServiceWorkflow", wfRequest, response, data);

        assertFalse(result);
        assertNull(response.get(Constants.STATUS)); // response untouched
    }

    @Test
    void testHandleProfileServiceWorkflow_IOException() {
        WfRequest wfRequest = buildWfRequest("name");
        Response response = new Response();
        Map<String, Object> data = new HashMap<>();

        boolean result = (boolean) ReflectionTestUtils.invokeMethod(
                workflowService, "handleProfileServiceWorkflow", wfRequest, response, data);

        assertFalse(result);
    }

    @ParameterizedTest
    @MethodSource("requestTypeProvider")
    void testAddRequestTypeInProfileWF(String fieldKey, String expectedRequestType) {
        WfRequest wfRequest = buildWfRequest(fieldKey);

        ReflectionTestUtils.invokeMethod(workflowService, "addRequestTypeInProfileWF", wfRequest);

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

    private WfRequest buildWfRequest(String fieldKey) {
        WfRequest request = new WfRequest();
        request.setUserId("user1");
        request.setUpdateFieldValues(
                List.of(new HashMap<>(Map.of(Constants.TO_VALUE, Map.of(fieldKey, "val1"))))
        );
        return request;
    }

    @Test
    void testHasCommunityModeratorRole_success() {
        // Prepare mock responseMap with COMMUNITY_MODERATOR role
        Map<String, Object> responseMap = Map.of(
                "result", Map.of(
                        "response", Map.of(
                                "content", List.of(
                                        Map.of(
                                                "organisations", List.of(
                                                        Map.of(
                                                                "roles", List.of("COMMUNITY_MODERATOR", "OTHER_ROLE")
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        boolean result = (boolean) ReflectionTestUtils.invokeMethod(
                workflowService,
                "hasCommunityModeratorRole",
                responseMap
        );

        assertTrue(result, "Should return true when COMMUNITY_MODERATOR role exists");
    }

    @Test
    void testSendNotification_group() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");
        wfRequest.setRootOrgId("rootOrg1");
        wfRequest.setAction("approve");

        when(configuration.getLmsServiceHost()).thenReturn("http://host/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/search");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/{userId}");

        Map<String, Object> mockedResponse =
                Map.of("result",
                        Map.of("response",
                                Map.of("content", List.of(Map.of("userId", "leader1")))));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), any(), any()))
                .thenReturn(mockedResponse);

        when(requestServiceImpl.fetchResultUsingGet(any()))
                .thenReturn(new HashMap<>());  // mock some response

        when(mapper.convertValue(any(), eq(Map.class)))
                .thenReturn(Map.of(Constants.RESULT, Map.of(Constants.RESPONSE, Map.of())));

        doNothing().when(notificationTriggerService).triggerNotification(any(), any(), any(), any(), any());

        ReflectionTestUtils.invokeMethod(workflowService, "sendNotification", "group", null, wfRequest);

        verify(notificationTriggerService, atLeastOnce()).triggerNotification(any(), any(), any(), any(), any());
    }


    @Test
    void testSendNotification_name() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");
        wfRequest.setRootOrgId("rootOrg1");
        wfRequest.setAction("approve");

        when(configuration.getLmsServiceHost()).thenReturn("http://host/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/search");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/{userId}");

        // mock POST (MDO leader search)
        Map<String, Object> mockedPostResponse =
                Map.of("result",
                        Map.of("response",
                                Map.of("content", List.of(Map.of("userId", "leader1")))));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), any(), any()))
                .thenReturn(mockedPostResponse);

        // mock GET (user profile)
        when(requestServiceImpl.fetchResultUsingGet(any()))
                .thenReturn(new HashMap<>());

        when(mapper.convertValue(any(), eq(Map.class)))
                .thenReturn(Map.of(Constants.RESULT, Map.of(Constants.RESPONSE, Map.of(
                        Constants.FIRST_NAME_CAMEL_CASE, "John Doe",
                        Constants.PROFILE_DETAILS, Map.of(
                                Constants.EMPLOYMENT_DETAILS, Map.of(
                                        Constants.DEPARTMENT_NAME, "IT Department"
                                )
                        )
                ))));

        doNothing().when(notificationTriggerService).triggerNotification(any(), any(), any(), any(), any());

        ReflectionTestUtils.invokeMethod(workflowService, "sendNotification", "name", "deptName", wfRequest);

        verify(notificationTriggerService, atLeastOnce()).triggerNotification(any(), any(), any(), any(), any());
    }


    @Test
    void testSendNotification_default() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");
        wfRequest.setRootOrgId("rootOrg1");

        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(workflowService, "sendNotification", "other", "dept", wfRequest));

    }

    @Test
    void testCallUserSearchApiToGetMdoleaderUserId() {
        when(configuration.getLmsServiceHost()).thenReturn("http://host/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("search");
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), any(), any())).thenReturn(
                Map.of(
                        "result", Map.of(
                                "response", Map.of(
                                        "content", List.of(
                                                Map.of("userId", "u1")
                                        )
                                )
                        )
                )
        );

        List<String> result = (List<String>) ReflectionTestUtils.invokeMethod(workflowService,
                "callUserSearchApiToGetMdoleaderUserId", "dept", "");

        assertEquals(1, result.size());
        assertEquals("u1", result.get(0));
    }

    @Test
    void testUserProfileRead_success() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setUserId("user1");

        when(configuration.getLmsServiceHost()).thenReturn("http://host/");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/{userId}");

        Map<String, Object> fakeResponse = new HashMap<>();
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(fakeResponse);

        Map<String, Object> expectedMap = Map.of(
                Constants.RESULT, Map.of(
                        Constants.RESPONSE, Map.of(
                                Constants.FIRST_NAME_CAMEL_CASE, "John",
                                Constants.PROFILE_DETAILS, Map.of(
                                        Constants.EMPLOYMENT_DETAILS, Map.of(
                                                Constants.DEPARTMENT_NAME, "IT"
                                        )
                                )
                        )
                )
        );

        when(mapper.convertValue(any(), eq(Map.class))).thenReturn(expectedMap);

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(workflowService, "userProfileRead", wfRequest.getUserId());

        assertNotNull(result);
        assertEquals("John", result.get(Constants.FIRST_NAME_CAMEL_CASE));
    }

    @Test
    void testUserProfileRead_emptyResponse() {
        when(configuration.getLmsServiceHost()).thenReturn("http://host/");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/{userId}");
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(null);

        Object result = ReflectionTestUtils.invokeMethod(workflowService,
                "userProfileRead", "user1");

        assertNull(result);
    }

    private WfRequest buildValidRequest() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setState("state");
        wfRequest.setApplicationId("app");
        wfRequest.setActorUserId("actor");
        wfRequest.setUserId("user");
        wfRequest.setAction("action");
        wfRequest.setUpdateFieldValues(List.of());
        wfRequest.setServiceName("service");
        return wfRequest;
    }
}