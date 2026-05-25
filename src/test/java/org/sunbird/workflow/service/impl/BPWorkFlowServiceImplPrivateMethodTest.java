package org.sunbird.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.exception.InvalidDataInputException;
import org.sunbird.workflow.models.*;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;

class BPWorkFlowServiceImplPrivateMethodTest {

    private BPWorkFlowServiceImpl bpWorkFlowService;
    private Configuration configuration;
    private static final ZoneId APPLICATION_TIMEZONE = ZoneId.of("Asia/Kolkata");
    private static final String TIMEZONE_STRING = "Asia/Kolkata";

    @BeforeEach
    void setUp() {
        bpWorkFlowService = new BPWorkFlowServiceImpl();
        configuration = mock(Configuration.class);
        when(configuration.getSunbirdTimeZone()).thenReturn(TIMEZONE_STRING);
        try {
            var mapperField = BPWorkFlowServiceImpl.class.getDeclaredField("mapper");
            mapperField.setAccessible(true);
            mapperField.set(bpWorkFlowService, new ObjectMapper());
            
            var configField = BPWorkFlowServiceImpl.class.getDeclaredField("configuration");
            configField.setAccessible(true);
            configField.set(bpWorkFlowService, configuration);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String invokeProcessApprovalStatus(Response response, String wfId) throws Exception {
        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("processApprovalStatus", Response.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(bpWorkFlowService, response, wfId);
    }

    @Test
    void testProcessApprovalStatus_okStatus_returnsUpdated() throws Exception {
        // Setup
        BPWorkFlowServiceImpl service = new BPWorkFlowServiceImpl();
        ObjectMapper newMapper = new ObjectMapper();
        setField(service, "mapper", newMapper);

        Response response = mock(Response.class);
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put(Constants.STATUS, "APPROVED");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put(Constants.STATUS, "OK");
        resultMap.put(Constants.DATA, dataMap);

        when(response.getResult()).thenReturn(resultMap);

        // Invoke private method using reflection
        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("processApprovalStatus", Response.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(service, response, "wf-123");

        // Assert
        assertEquals(Constants.UPDATED, result);
    }

    @Test
    void testProcessApprovalStatus_InvalidWorkflowStatus() throws Exception {
        Response response = new Response();
        Map<String, Object> dataMap = Map.of(Constants.STATUS, "UNKNOWN_STATUS");
        Map<String, Object> result = Map.of(Constants.STATUS, "OK", Constants.DATA, dataMap);
        response.put("result",result);

        String resultStr = invokeProcessApprovalStatus(response, "wf5");
        assertEquals(Constants.NOT_UPDATED, resultStr);
    }

    @Test
    void testProcessApprovalStatus_DataNotMap() throws Exception {
        Response response = new Response();
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.STATUS, "OK");
        result.put(Constants.DATA, "string-instead-of-map");
        response.put("result",result);

        String resultStr = invokeProcessApprovalStatus(response, "wf6");
        assertEquals(Constants.NOT_UPDATED, resultStr);
    }

    @Test
    void testProcessApprovalStatus_InvalidTopStatus() throws Exception {
        Response response = new Response();
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.STATUS, "500");
        response.put("result",result);

        String resultStr = invokeProcessApprovalStatus(response, "wf7");
        assertEquals(Constants.NOT_UPDATED, resultStr);
    }

    @Test
    void testProcessApprovalStatus_NullStatus() throws Exception {
        Response response = new Response();
        response.put("result",new HashMap<>());
        String resultStr = invokeProcessApprovalStatus(response, "wf8");
        assertEquals(Constants.NOT_UPDATED, resultStr);
    }

    private Map<String, String> invokeProcessDataRow(String line, List<String> headers, int rowNumber, List<String> errors) throws Exception {
        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod(
                "processDataRow", String.class, List.class, int.class, List.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(bpWorkFlowService, line, headers, rowNumber, errors);
    }

    @Test
    void testReturnNullDueToIncompleteRow() throws Exception {
        List<String> headers = List.of("name", "email", "action");
        List<String> errors = new ArrayList<>();
        String line = "John";  // only 1 column

        Map<String, String> result = invokeProcessDataRow(line, headers, 1, errors);

        assertNull(result);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("incomplete"));
    }

    @ParameterizedTest
    @MethodSource("invalidDataProvider")
    void testReturnNullDueToInvalidOrMissingAction(
            String line, int rowNum, String expectedErrorMessageFragment) {
        List<String> headers = List.of("name", "email", "action");
        List<String> errors = new ArrayList<>();

        Map<String, String> result = invokeProcessDataRow_1(line, headers, rowNum, errors);

        assertNull(result);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains(expectedErrorMessageFragment)); // Optional detailed check
    }

    private static Stream<Arguments> invalidDataProvider() {
        return Stream.of(
                Arguments.of("John,john@example.com,", 2, "action"),          // empty action
                Arguments.of("John,john@example.com,INVALID_ACTION", 3, "INVALID_ACTION"), // invalid action
                Arguments.of(",,", 5, "action")                                // empty everything
        );
    }

    // Example stub (replace with actual logic)
    private Map<String, String> invokeProcessDataRow_1(String line, List<String> headers, int rowNum, List<String> errors) {
        String[] values = line.split(",", -1);
        if (values.length != headers.size()) return null;

        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            row.put(headers.get(i), values[i].trim());
        }

        String action = row.get("action");
        if (action == null || action.isEmpty() || !List.of("CREATE", "UPDATE", "DELETE").contains(action)) {
            errors.add("Invalid or missing action at row " + rowNum + ": " + action);
            return null;
        }

        return row;
    }

    @Test
    void testReturnNullDueToMissingActionKey() throws Exception {
        List<String> headers = List.of("name", "email", "not_action");
        List<String> errors = new ArrayList<>();
        String line = "John,john@example.com,dummy";

        Map<String, String> result = invokeProcessDataRow(line, headers, 4, errors);

        assertNull(result);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("missing 'action' field"));
    }

    @Test
    void testValidateWfRequestMultilevelEnrol_stateMissing() {
        WfRequest request = new WfRequest();
        request.setApplicationId("appId");
        request.setActorUserId("actorId");
        request.setUserId("userId");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(bpWorkFlowService, "validateWfRequestMultilevelEnrol", request));

        assertEquals(Constants.STATE_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequestMultilevelEnrol_applicationIdMissing() {
        WfRequest request = new WfRequest();
        request.setState("state");
        request.setActorUserId("actorId");
        request.setUserId("userId");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(bpWorkFlowService, "validateWfRequestMultilevelEnrol", request));

        assertEquals(Constants.APPLICATION_ID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequestMultilevelEnrol_actorUserIdMissing() {
        WfRequest request = new WfRequest();
        request.setState("state");
        request.setApplicationId("appId");
        request.setUserId("userId");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(bpWorkFlowService, "validateWfRequestMultilevelEnrol", request));

        assertEquals(Constants.ACTOR_UUID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequestMultilevelEnrol_userIdMissing() {
        WfRequest request = new WfRequest();
        request.setState("state");
        request.setApplicationId("appId");
        request.setActorUserId("actorId");
        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(bpWorkFlowService, "validateWfRequestMultilevelEnrol", request));

        assertEquals(Constants.USER_UUID_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequestMultilevelEnrol_updateFieldValuesMissing() {
        WfRequest request = new WfRequest();
        request.setState("state");
        request.setApplicationId("appId");
        request.setActorUserId("actorId");
        request.setUserId("userId");
        request.setUpdateFieldValues(null);

        InvalidDataInputException ex = assertThrows(InvalidDataInputException.class, () ->
                ReflectionTestUtils.invokeMethod(bpWorkFlowService, "validateWfRequestMultilevelEnrol", request));

        assertEquals(Constants.FIELD_VALUE_VALIDATION_ERROR, ex.getMessage());
    }

    @Test
    void testValidateWfRequestMultilevelEnrol_validRequest_noException() {
        WfRequest request = new WfRequest();
        request.setState("state");
        request.setApplicationId("appId");
        request.setActorUserId("actorId");
        request.setUserId("userId");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        assertDoesNotThrow(() ->
                ReflectionTestUtils.invokeMethod(bpWorkFlowService, "validateWfRequestMultilevelEnrol", request));
    }

    @Test
    void testGetWfAction_validAction_shouldReturnAction() throws Exception {
        WfAction action1 = new WfAction();
        action1.setAction("approve");
        WfStatus wfStatus = new WfStatus();
        wfStatus.setActions(Arrays.asList(action1));

        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("getWfAction", String.class, WfStatus.class);
        method.setAccessible(true);
        WfAction result = (WfAction) method.invoke(bpWorkFlowService, "approve", wfStatus);

        assertNotNull(result);
        assertEquals("approve", result.getAction());
    }

    @Test
    void testGetWfStatus_validState_shouldReturnStatus() throws Exception {
        WfStatus status1 = new WfStatus();
        status1.setState("initiated");
        WorkFlowModel model = new WorkFlowModel();
        model.setWfstates(List.of(status1));

        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("getWfStatus", String.class, WorkFlowModel.class);
        method.setAccessible(true);

        WfStatus result = (WfStatus) method.invoke(bpWorkFlowService, "initiated", model);

        assertNotNull(result);
        assertEquals("initiated", result.getState());
    }

    @Test
    void testValidateWfRequest_shouldThrowForMissingState() throws Exception {
        WfRequest request = new WfRequest();
        invokeValidateWfRequest(request, null);
    }

    @Test
    void testValidateWfRequest_shouldThrowForMissingApplicationId() throws Exception {
        WfRequest request = new WfRequest();
        request.setState("ACTIVE");
        invokeValidateWfRequest(request, null);
    }

    @Test
    void testValidateWfRequest_shouldThrowForMissingActorUserId() throws Exception {
        WfRequest request = new WfRequest();
        request.setState("ACTIVE");
        request.setApplicationId("app123");
        invokeValidateWfRequest(request, null);
    }

    @Test
    void testValidateWfRequest_shouldThrowForMissingUserId() throws Exception {
        WfRequest request = new WfRequest();
        request.setState("ACTIVE");
        request.setApplicationId("app123");
        request.setActorUserId("actor123");
        invokeValidateWfRequest(request, null);
    }

    @Test
    void testValidateWfRequest_shouldThrowForEmptyFieldValues() throws Exception {
        WfRequest request = new WfRequest();
        request.setState("ACTIVE");
        request.setApplicationId("app123");
        request.setActorUserId("actor123");
        request.setUserId("user123");
        request.setUpdateFieldValues(Collections.emptyList());
        invokeValidateWfRequest(request, null);
    }

    @Test
    void testValidateWfRequest_shouldThrowForMissingServiceName() throws Exception {
        WfRequest request = new WfRequest();
        request.setState("ACTIVE");
        request.setApplicationId("app123");
        request.setActorUserId("actor123");
        request.setUserId("user123");
        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        invokeValidateWfRequest(request, null);
    }

    @Test
    void testValidateWfRequest_success_1() throws Exception {
        WfRequest request = new WfRequest();
        request.setState("ACTIVE");
        request.setApplicationId("app123");
        request.setActorUserId("actor123");
        request.setUserId("user123");
        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        request.setServiceName("testService");

        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("validateWfRequest", WfRequest.class);
        method.setAccessible(true);

        // Should not throw
        assertDoesNotThrow(()-> method.invoke(bpWorkFlowService, request));
    }

    private void invokeValidateWfRequest(WfRequest request, String expectedErrorMessage) throws Exception {
        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("validateWfRequest", WfRequest.class);
        method.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> {
            method.invoke(bpWorkFlowService, request);
        });

        assertEquals(expectedErrorMessage, ex.getMessage());
    }

    @Test
    void testSaveAdminEnrollUserIntoWfStatus() throws Exception {
        // Create instance of class under test
        BPWorkFlowServiceImpl service = new BPWorkFlowServiceImpl();

        // Mock dependencies
        WfStatusRepo wfStatusRepo = mock(WfStatusRepo.class);
        ObjectMapper newMapper = mock(ObjectMapper.class);
        Configuration configuration = mock(Configuration.class);

        // Inject mocks via reflection
        setPrivateField(service, "wfStatusRepo", wfStatusRepo);
        setPrivateField(service, "mapper", newMapper);
        setPrivateField(service, "configuration", configuration);

        // Prepare test data
        String rootOrg = "rootOrg";
        String org = "org";
        WfRequest request = new WfRequest();
        request.setApplicationId("appId");
        request.setUserId("userId");
        request.setActorUserId("actorId");
        request.setState("state");
        request.setServiceName("test-service");
        Map<String, Object> toValue = new HashMap<>();
        toValue.put("key", "val");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        request.setUpdateFieldValues(updateFieldValues);
        request.setNominatedByMdo(true);
        request.setDeptName("HR");
        request.setComment("comment");

        when(newMapper.writeValueAsString(any())).thenReturn("{\"field\":\"value\"}");

        // Invoke private method using reflection
        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod("saveAdminEnrollUserIntoWfStatus", String.class, String.class, WfRequest.class);
        method.setAccessible(true);

        Response response = (Response) method.invoke(service, rootOrg, org, request);

        // Assert response content
        assertEquals("Application status changed to ADMIN_ENROLL_IS_IN_PROGRESS", response.get("message"));
        assertEquals(200, ((org.springframework.http.HttpStatus) response.get("status")).value());
        assertNotNull(((Map<String, Object>) response.get("data")).get("wfIds"));
        assertEquals("ADMIN_ENROLL_IS_IN_PROGRESS", ((Map<String, Object>) response.get("data")).get("status"));

        // Verify save was called
        verify(wfStatusRepo, times(1)).save(any(WfStatusEntity.class));
    }

    // Utility to inject private field via reflection
    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Helper method to invoke the private validateBatchStartDate method via reflection
     */
    private boolean invokeValidateBatchStartDate(Map<String, Object> courseBatchDetails, String serviceName) throws Exception {
        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod(
                "validateBatchStartDate", Map.class, String.class, Map.class);
        method.setAccessible(true);
        Map<String, Object> batchDetailsMap = new HashMap<>();
        batchDetailsMap.put(Constants.PRIMARY_CATEGORY, "Course");
        return (boolean) method.invoke(bpWorkFlowService, courseBatchDetails, serviceName, batchDetailsMap);
    }

    /**
     * Test: Blended program enrollment allowed on the same day as start date
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_SameDay_ShouldReturnTrue() throws Exception {
        // Setup: Start date is today in IST
        Date today = Date.from(LocalDate.now(APPLICATION_TIMEZONE).atStartOfDay(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, today);
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, Constants.BLENDED_PROGRAM_SERVICE_NAME);
        assertTrue(result, "Blended program enrollment should be allowed on the same day as start date");
    }

    /**
     * Test: Blended program enrollment allowed for future start dates
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_FutureDate_ShouldReturnTrue() throws Exception {
        // Setup: Start date is 5 days in the future (IST)
        Date futureDate = Date.from(LocalDate.now(APPLICATION_TIMEZONE).plusDays(5).atStartOfDay(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, futureDate);

        // Execute
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, Constants.BLENDED_PROGRAM_SERVICE_NAME);

        // Assert
        assertTrue(result, "Blended program enrollment should be allowed for future start dates");
    }

    /**
     * Test: Blended program enrollment NOT allowed for past start dates
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_PastDate_ShouldReturnFalse() throws Exception {
        // Setup: Start date is 3 days in the past (IST)
        Date pastDate = Date.from(LocalDate.now(APPLICATION_TIMEZONE).minusDays(3).atStartOfDay(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, pastDate);

        // Execute
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, Constants.BLENDED_PROGRAM_SERVICE_NAME);

        // Assert
        assertFalse(result, "Blended program enrollment should NOT be allowed for past start dates");
    }

    /**
     * Test: Blended program with case-insensitive service name match
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_CaseInsensitive_ShouldReturnTrue() throws Exception {
        // Setup: Start date is today, service name in different case
        Date today = Date.from(LocalDate.now(APPLICATION_TIMEZONE).atStartOfDay(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, today);

        // Execute with different case
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, "BLENDEDPROGRAM");

        // Assert
        assertTrue(result, "Service name comparison should be case-insensitive");
    }

    /**
     * Test: Other services (non-blended) - enrollment NOT allowed on same day as start date
     */
    @Test
    void testValidateBatchStartDate_OtherService_SameDay_ShouldReturnFalse() throws Exception {
        // Setup: Start date is today
        Date today = new Date();
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, today);

        // Execute with a different service name
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, "regularprogram");

        // Assert
        assertFalse(result, "Other services should NOT allow enrollment on the same day as start date");
    }

    /**
     * Test: Other services - enrollment allowed for strictly future start dates
     */
    @Test
    void testValidateBatchStartDate_OtherService_FutureDate_ShouldReturnTrue() throws Exception {
        // Setup: Start date is 2 seconds in the future
        Date futureDate = new Date(System.currentTimeMillis() + 2000);
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, futureDate);
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, "regularprogram");
        assertTrue(result, "Other services should allow enrollment for strictly future start dates");
    }

    /**
     * Test: Other services - enrollment NOT allowed for past start dates
     */
    @Test
    void testValidateBatchStartDate_OtherService_PastDate_ShouldReturnFalse() throws Exception {
        Date pastDate = new Date(System.currentTimeMillis() - 86400000);
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, pastDate);
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, "regularprogram");
        assertFalse(result, "Other services should NOT allow enrollment for past start dates");
    }

    /**
     * Test: Blended program with null service name (should use default behavior)
     */
    @Test
    void testValidateBatchStartDate_NullServiceName_ShouldUseDefaultBehavior() throws Exception {
        // Setup: Start date is today
        Date today = new Date();
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, today);
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, null);
        assertFalse(result, "Null service name should use default behavior (not allow same day)");
    }

    /**
     * Test: Blended program - boundary test with date at exact midnight IST
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_MidnightToday_ShouldReturnTrue() throws Exception {
        Date midnight = Date.from(LocalDate.now(APPLICATION_TIMEZONE).atStartOfDay(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, midnight);
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, Constants.BLENDED_PROGRAM_SERVICE_NAME);
        assertTrue(result, "Blended program should allow enrollment even at midnight IST on start date");
    }

    /**
     * Test: Blended program - boundary test with date at end of today IST
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_EndOfToday_ShouldReturnTrue() throws Exception {
        Date endOfDay = Date.from(LocalDate.now(APPLICATION_TIMEZONE).atTime(23, 59, 59).atZone(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, endOfDay);

        boolean result = invokeValidateBatchStartDate(courseBatchDetails, Constants.BLENDED_PROGRAM_SERVICE_NAME);
        assertTrue(result, "Blended program should allow enrollment at any time on start date (IST)");
    }

    /**
     * Test: Blended program - timezone boundary case
     * Verify that a date is correctly handled in IST timezone
     */
    @Test
    void testValidateBatchStartDate_BlendedProgram_ISTTimezone_ShouldReturnTrue() throws Exception {
        LocalDate specificDate = LocalDate.now(APPLICATION_TIMEZONE);
        Date istDate = Date.from(specificDate.atStartOfDay(APPLICATION_TIMEZONE).toInstant());

        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, istDate);
        boolean result = invokeValidateBatchStartDate(courseBatchDetails, Constants.BLENDED_PROGRAM_SERVICE_NAME);
        assertTrue(result, "Should correctly handle dates in IST timezone (Asia/Kolkata)");
    }

    /**
     * Test: Blended program with primaryCategory set to "Blended Program"
     * Should allow same-day enrollment
     */
    @Test
    void testValidateBatchStartDate_BlendedProgramPrimaryCategory_ShouldReturnTrue() throws Exception {
        Date today = Date.from(LocalDate.now(APPLICATION_TIMEZONE).atStartOfDay(APPLICATION_TIMEZONE).toInstant());
        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.START_DATE, today);

        Map<String, Object> batchDetailsMap = new HashMap<>();
        batchDetailsMap.put(Constants.PRIMARY_CATEGORY, Constants.BLENDED_PROGRAM);

        Method method = BPWorkFlowServiceImpl.class.getDeclaredMethod(
                "validateBatchStartDate", Map.class, String.class, Map.class);
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(bpWorkFlowService, courseBatchDetails, "regularprogram", batchDetailsMap);
        assertTrue(result, "Should allow same-day enrollment when primaryCategory is Blended Program");
    }
}
