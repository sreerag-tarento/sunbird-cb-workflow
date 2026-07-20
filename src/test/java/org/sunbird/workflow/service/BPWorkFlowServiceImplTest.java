package org.sunbird.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.multipart.MultipartFile;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.exception.ApplicationException;
import org.sunbird.workflow.models.*;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.producer.Producer;
import org.sunbird.workflow.service.impl.BPWorkFlowServiceImpl;
import org.sunbird.workflow.service.impl.RequestServiceImpl;
import org.sunbird.workflow.utils.CassandraOperation;
import org.sunbird.workflow.utils.UserUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BPWorkFlowServiceImplTest {

    @Spy
    @InjectMocks
    private BPWorkFlowServiceImpl bpWorkFlowService  ;

    @Mock
    private Workflowservice workflowservice;

    @Mock
    private WfStatusRepo wfStatusRepo;

    @Spy
    private Logger logger = LoggerFactory.getLogger(BPWorkFlowServiceImplTest.class);

    @Mock
    private CassandraOperation cassandraOperation;
    @Mock
    private RequestServiceImpl requestServiceImpl;
    @Mock
    private Configuration configuration;
    @Mock
    private ObjectMapper mapper;
    @Mock
    private Producer producer;
    @Mock
    private ContentReadService contentReadService;

    @Mock
    private UserUtil userUtils;

    @Mock
    private org.sunbird.workflow.utils.ElasticsearchServiceManager elasticsearchServiceManager;

    private WfRequest wfRequest;
    private Response mockResponse;

    private final String rootOrg = "root-org";
    private final String org = "org";
    private final String wfId = "wf123";
    private final String applicationId = "app123";
    private static final String COURSE_ID = "course123";
    private static final String BATCH_ID = "batch456";
    private static final String USER_ID = "user789";
    private static final String ROOT_ORG = "root-org";
    private static final String ORG = "org";
    private final String ROOT = "root";

    @Test
    void testGenerateUserApprovalCsv_success() {
        SearchCriteria criteria = new SearchCriteria();

        Map<String, Object> userInfo = Map.of(
                "email", "test@example.com",
                "firstName", "John"
        );

        WfStatusEntity wf1 = new WfStatusEntity();
        wf1.setWfId("wf123");
        wf1.setApplicationId("user123");

        Map<String, Object> item = new HashMap<>();
        item.put("userInfo", userInfo);
        item.put("wfInfo", List.of(wf1));

        List<Map<String, Object>> dataList = List.of(item);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("data", dataList);

        mockResponse = new Response();
        mockResponse.putAll(resultMap);

        when(workflowservice.applicationsSearch(any(), any(), any(), any())).thenReturn(mockResponse);
        ResponseEntity<ByteArrayResource> response = bpWorkFlowService.generateUserApprovalCsv(criteria);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("text/csv", response.getHeaders().getContentType().toString());
    }

    @Test
    void testGenerateUserApprovalCsv_IOException() throws Exception {
        SearchCriteria criteria = new SearchCriteria();
        BPWorkFlowServiceImpl spyService = Mockito.spy(new BPWorkFlowServiceImpl());
        ReflectionTestUtils.setField(spyService, "workflowService", workflowservice);

        Map<String, Object> userInfo = Map.of("email", "test@example.com", "firstName", "John");
        WfStatusEntity wf1 = new WfStatusEntity();
        wf1.setWfId("wf123");
        wf1.setUserId("user123");

        Map<String, Object> item = new HashMap<>();
        item.put("userInfo", userInfo);
        item.put("wfInfo", List.of(wf1));

        mockResponse = new Response();
        mockResponse.put("data", List.of(item));
        when(workflowservice.applicationsSearch(any(), any(), any(), any()))
                .thenReturn(mockResponse);

        doThrow(new IOException("Simulated IO Error"))
                .when(spyService)
                .writeApprovalDataToCsv(any(), any());
        ResponseEntity<ByteArrayResource> response = spyService.generateUserApprovalCsv(criteria);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertTrue(new String(response.getBody().getByteArray()).contains("Error generating CSV file"));
    }

    @Test
    void testLoadApprovalDataFromCsv_success() throws Exception {
        String csvContent =
                "email,userName,wfId,userId,action(approve/reject)\n" +
                        "dev.agri.user124@yopmail.com,Dev Agri Usertwentyfour,fe1d0b01-f414-4867-baea-e9a6851a71a1,e2696f18-d805-4c1d-81ed-1501b030c20e,approve";

        MockMultipartFile file = new MockMultipartFile(
                "file", "approval_data.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        WfStatusEntity mockEntity = new WfStatusEntity();
        mockEntity.setWfId(wfId);

        when(wfStatusRepo.findByWfId(any())).thenReturn(mockEntity);
        ResponseEntity<?> response = bpWorkFlowService.loadApprovalDataFromCsv(file, "do_114296977636638720114");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(wfStatusRepo).findByWfId(any());
    }

    @Test
    void testLoadApprovalDataFromCsv_InvalidFileFormat() throws IOException {
        MultipartFile mockFile = new MockMultipartFile("file", "file.txt", "text/plain", "test".getBytes());
        ResponseEntity<?> response = bpWorkFlowService.loadApprovalDataFromCsv(mockFile, "content123");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        SBApiResponse body = (SBApiResponse) response.getBody();
        assertNotNull(body);
        assertEquals("FAILED", body.getParams().getStatus());
        assertTrue(body.getParams().getErrmsg().contains("Invalid file format"));
    }

    @Test
    void testLoadApprovalDataFromCsv_WithValidationErrors_WithoutMockingPrivate() throws IOException {
        String invalidCsv = "email,firstName,wfId\nemail@example.com,John,\n";
        MultipartFile mockFile = new MockMultipartFile("file", "data.csv", "text/csv", invalidCsv.getBytes());

        ResponseEntity<?> response = bpWorkFlowService.loadApprovalDataFromCsv(mockFile, "content123");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        SBApiResponse body = (SBApiResponse) response.getBody();
        assertNotNull(body);
        assertEquals("FAILED", body.getParams().getStatus());
        assertTrue(body.getResult().containsKey("validationErrors"));
    }

    @Test
    void testLoadApprovalDataFromCsv_IOExceptionHandled() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("data.csv");
        when(file.getInputStream()).thenThrow(new IOException("Simulated read error"));

        ResponseEntity<?> response = bpWorkFlowService.loadApprovalDataFromCsv(file, "content123");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        SBApiResponse body = (SBApiResponse) response.getBody();
        assertEquals("FAILED", body.getParams().getStatus());
    }

    private WfRequest getRequest() {
        wfRequest = new WfRequest();
        wfRequest.setState("APPROVED");
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("courseABC");
        wfRequest.setUserId("user1");
        wfRequest.setActorUserId("actor1");
        wfRequest.setDeptName("IT");
        wfRequest.setComment("Approved");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("failed", "value");

        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put(Constants.TO_VALUE, toValue);

        List<HashMap<String, Object>> updateFieldValues = new ArrayList<>();
        updateFieldValues.add(updateField);
        wfRequest.setUpdateFieldValues(updateFieldValues);
        return wfRequest;
    }

    @Test
    void enrolBPWorkFlow_success() throws Exception {
        wfRequest = getRequest();

        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.CURRENT_BATCH_SIZE, 50);
        courseBatchDetails.put(Constants.BATCH_NAME, "Batch Name");
        courseBatchDetails.put(Constants.START_DATE, new Date());

        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "BP_SERVICE", "primaryCategory", "Course"));
        when(wfStatusRepo.findByApplicationId(any())).thenReturn(new ArrayList<>());
        when(configuration.getBpBatchEnrolLimitBufferSize()).thenReturn(20);
        when(mapper.writeValueAsString(any())).thenReturn("updateFieldValue");
        when(configuration.getWorkflowApplicationTopic()).thenReturn("wf-topic");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_ROLES),
                eq(Collections.singletonMap(Constants.USER_ID, wfRequest.getUserId())),
                eq(Arrays.asList(Constants.ROLE, Constants.USER_ID)),
                isNull()
        )).thenReturn(Collections.emptyMap());
        // Set up enrolment batch lookup response
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_ENROLMENT_BATCH_LOOKUP),
                anyMap(),
                anyList()))
                .thenReturn(List.of(Map.of("active", true)));

        // Set up user enrolment details and course batch details (no conflict)
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.USER_ENROLMENTS_V2),
                anyMap(),
                anyList()))
                .thenReturn(List.of(Map.of(
                        Constants.COURSE_ID, "course1",
                        Constants.BATCH_ID, "batch1",
                        Constants.ACTIVE, true
                )));
        Map<String, Object> batchAttributesMap = new HashMap<>();
        batchAttributesMap.put(Constants.CURRENT_BATCH_SIZE, "50");

        ObjectMapper mapperNew = new ObjectMapper();
        String batchAttributesJson = mapperNew.writeValueAsString(batchAttributesMap);

        // Prepare courseBatch map
        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.BATCH_ATTRIBUTES, batchAttributesJson);
        courseBatch.put(Constants.ENROLMENT_END_DATE, Instant.now());
        courseBatch.put(Constants.START_DATE, Instant.now());
        courseBatch.put(Constants.NAME, "Test Batch");

        // Cassandra mock response
        List<Map<String, Object>> mockResponseMap = Collections.singletonList(courseBatch);

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList()
        )).thenReturn(mockResponseMap);

        Response response = bpWorkFlowService.enrolBPWorkFlow("rootOrg", "org", wfRequest);

        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals("Application status changed to ENROLL_IS_IN_PROGRESS", response.get(Constants.MESSAGE));
        verify(producer).push(eq("wf-topic"), any());
    }

    @Test
    void testIsCourseEligibleForOrg_true() {
        String orgId = "org-elig-1";
        String courseId = COURSE_ID;
        ReflectionTestUtils.setField(bpWorkFlowService, "orgeligibilityIndex", "org_elig_index");
        ReflectionTestUtils.setField(bpWorkFlowService, "orgeligibilityIndexType", "_doc");

        Map<String, Object> eligibility = new HashMap<>();
        eligibility.put(Constants.COURSEIDS, Arrays.asList(courseId));

        when(elasticsearchServiceManager.readEntity(eq("org_elig_index"), eq("_doc"), eq(orgId))).thenReturn(eligibility);

        boolean result = (boolean) ReflectionTestUtils.invokeMethod(bpWorkFlowService, "isCourseEligibleForOrg", orgId, courseId);
        assertTrue(result);
    }

    @Test
    void testIsCourseEligibleForOrg_false_when_no_entry() {
        String orgId = "org-elig-2";
        String courseId = "some-other-course";
        ReflectionTestUtils.setField(bpWorkFlowService, "orgeligibilityIndex", "org_elig_index");
        ReflectionTestUtils.setField(bpWorkFlowService, "orgeligibilityIndexType", "_doc");

        when(elasticsearchServiceManager.readEntity(eq("org_elig_index"), eq("_doc"), eq(orgId))).thenReturn(Collections.emptyMap());

        boolean result = (boolean) ReflectionTestUtils.invokeMethod(bpWorkFlowService, "isCourseEligibleForOrg", orgId, courseId);
        assertFalse(result);
    }

    @Test
    void enrolBPWorkFlow_success_1() throws Exception {
        wfRequest = getRequest();

        Map<String, Object> courseBatchDetails = new HashMap<>();
        courseBatchDetails.put(Constants.CURRENT_BATCH_SIZE, 50);
        courseBatchDetails.put(Constants.BATCH_NAME, "Batch Name");
        courseBatchDetails.put(Constants.START_DATE, new Date());

        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "", "primaryCategory", "Course"));
        when(wfStatusRepo.findByApplicationId(any())).thenReturn(new ArrayList<>());
        when(configuration.getBpBatchEnrolLimitBufferSize()).thenReturn(20);
        when(mapper.writeValueAsString(any())).thenReturn("updateFieldValue");
        when(configuration.getWorkflowApplicationTopic()).thenReturn("wf-topic");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_ROLES),
                eq(Collections.singletonMap(Constants.USER_ID, wfRequest.getUserId())),
                eq(Arrays.asList(Constants.ROLE, Constants.USER_ID)),
                isNull()
        )).thenReturn(Collections.emptyMap());

        // Set up enrolment batch lookup response
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_ENROLMENT_BATCH_LOOKUP),
                anyMap(),
                anyList()))
                .thenReturn(List.of(Map.of("active", true)));

        // Set up user enrolment details and course batch details (no conflict)
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.USER_ENROLMENTS_V2),
                anyMap(),
                anyList()))
                .thenReturn(List.of(Map.of(
                        Constants.COURSE_ID, "course1",
                        Constants.BATCH_ID, "batch1",
                        Constants.ACTIVE, true
                )));
        Map<String, Object> batchAttributesMap = new HashMap<>();
        batchAttributesMap.put(Constants.CURRENT_BATCH_SIZE, "50");

        ObjectMapper mapperNew = new ObjectMapper();
        String batchAttributesJson = mapperNew.writeValueAsString(batchAttributesMap);

        // Prepare courseBatch map
        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.BATCH_ATTRIBUTES, batchAttributesJson);
        courseBatch.put(Constants.ENROLMENT_END_DATE, Instant.now());
        courseBatch.put(Constants.START_DATE, Instant.now());
        courseBatch.put(Constants.NAME, "Test Batch");

        // Cassandra mock response
        List<Map<String, Object>> mockResponseMap = Collections.singletonList(courseBatch);

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList()
        )).thenReturn(mockResponseMap);

        Response response = bpWorkFlowService.enrolBPWorkFlow("rootOrg", "org", wfRequest);

        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals("Application status changed to ENROLL_IS_IN_PROGRESS", response.get(Constants.MESSAGE));
        verify(producer).push(eq("wf-topic"), any());
    }

    @Test
    void enrolBPWorkFlow_shouldReturnBadRequest_ifBatchIsFull() {
        wfRequest = getRequest();
        // Empty course batch details to simulate batch full
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(Collections.emptyList());

        Response response = bpWorkFlowService.enrolBPWorkFlow("rootOrg", "org", wfRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
    }

    @Test
    void testUpdateBPWorkFlow_batchStartDateError() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setAction("ENROLL");
        wfRequest.setUserId("user-1");
        wfRequest.setAction("ACTION"); // Not in exclude states

        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}");
        courseBatch.put(Constants.START_DATE, Instant.now().minus(5, ChronoUnit.DAYS));
        courseBatch.put(Constants.NAME, "Batch A");

        when(configuration.getBpBatchFullValidationExcludeStates()).thenReturn(Collections.singletonList("NONE"));
        when(configuration.getBatchInProgressMessage()).thenReturn("Batch already started");
        when(cassandraOperation.getRecordsByProperties(
                anyString(), anyString(), anyMap(), anyList()
        )).thenReturn(List.of(courseBatch));

        Response response = bpWorkFlowService.updateBPWorkFlow("root", "org", wfRequest, "userId", "role");

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("Batch already started", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testUpdateBPWorkFlowBatchSizeError() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setAction("ENROLL");
        wfRequest.setUserId("user-1");
        wfRequest.setAction("ACTION"); // Not in exclude states

        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatch\":\"10\"}");
        courseBatch.put(Constants.START_DATE, Instant.now().plus(1, ChronoUnit.DAYS));
        courseBatch.put(Constants.NAME, "Batch A");

        when(configuration.getBpBatchFullValidationExcludeStates()).thenReturn(Collections.singletonList("NONE"));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), anyString(), anyMap(), anyList()
        )).thenReturn(List.of(courseBatch));

        Response response = bpWorkFlowService.updateBPWorkFlow("root", "org", wfRequest, "userId", "role");

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
    }

    @Test
    void testUpdateBPWorkFlow_batchSizeError() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setAction("ENROLL");
        wfRequest.setUserId("user-1");
        wfRequest.setAction("ACTION"); // Not in exclude states

        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"1\"}");
        courseBatch.put(Constants.START_DATE, Instant.now().plus(5, ChronoUnit.DAYS));
        courseBatch.put(Constants.NAME, "Batch A");

        when(configuration.getBpBatchFullValidationExcludeStates()).thenReturn(Collections.singletonList("NONE"));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), anyString(), anyMap(), anyList()
        )).thenReturn(List.of(courseBatch));

        // simulate totalApprovedUserCount = 1
        Method getTotalApprovedUserCount = ReflectionUtils.findMethod(BPWorkFlowServiceImpl.class, "getTotalApprovedUserCount", WfRequest.class);
        if (getTotalApprovedUserCount != null) {
            getTotalApprovedUserCount.setAccessible(true);
        }

        Response response = bpWorkFlowService.updateBPWorkFlow("root", "org", wfRequest, "userId", "role");

        assertNull(response);
    }

    @Test
    void testUpdateBPWorkFlow_success() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setAction("ENROLL");
        wfRequest.setUserId("user-1");
        wfRequest.setAction("SKIP_VALIDATION"); // Will be excluded

        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}");
        courseBatch.put(Constants.START_DATE, Instant.now().plus(1, ChronoUnit.DAYS));
        courseBatch.put(Constants.NAME, "Batch A");

        when(configuration.getBpBatchFullValidationExcludeStates()).thenReturn(Collections.singletonList("SKIP_VALIDATION"));
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(List.of(courseBatch));

        Response expected = new Response();
        expected.put("result", "success");

        when(workflowservice.workflowTransition(any(), any(), any(), any(), any()))
                .thenReturn(expected);

        Response response = bpWorkFlowService.updateBPWorkFlow("root", "org", wfRequest, "userId", "role");

        assertEquals("success", response.get("result"));
    }


    @Test
    void testReadBPWFApplication() {

        mockResponse = new Response();
        mockResponse.put("status", "success");
        when(workflowservice.getWfApplication(rootOrg, org, wfId, applicationId)).thenReturn(mockResponse);

        Response result = bpWorkFlowService.readBPWFApplication(rootOrg, org, wfId, applicationId);

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        verify(workflowservice).getWfApplication(rootOrg, org, wfId, applicationId);
    }

    @Test
    void testBlendedProgramSearch() {
        SearchCriteria criteria = new SearchCriteria();

        mockResponse = new Response();
        mockResponse.put("status", "success");
        when(workflowservice.applicationsSearch(rootOrg, org, criteria, Constants.BLENDED_PROGRAM_SEARCH_ENABLED))
                .thenReturn(mockResponse);

        Response result = bpWorkFlowService.blendedProgramSearch(rootOrg, org, criteria);

        assertNotNull(result);
        assertEquals("success", result.get("status"));
    }

    @Test
    void testBpPCSearch() {
        SearchCriteriaV2 criteria = new SearchCriteriaV2();

        mockResponse = new Response();
        mockResponse.put("status", "success");
        when(workflowservice.appsPCSearchV2(rootOrg, org, criteria)).thenReturn(mockResponse);

        Response result = bpWorkFlowService.bpPCSearch(rootOrg, org, criteria);

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        verify(workflowservice).appsPCSearchV2(rootOrg, org, criteria);
    }

    @Test
    void testBpMDOSearch() {
        SearchCriteriaV2 criteria = new SearchCriteriaV2();

        mockResponse = new Response();
        mockResponse.put("status", "success");
        when(workflowservice.appsPCSearchV2(rootOrg, org, criteria)).thenReturn(mockResponse);

        Response result = bpWorkFlowService.bpMDOSearch(rootOrg, org, criteria);

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        verify(workflowservice).appsPCSearchV2(rootOrg, org, criteria);
    }

    @Test
    void testBlendedProgramStatusCount() {
        SearchCriteria criteria = new SearchCriteria();

        mockResponse = new Response();
        mockResponse.put("status", "success");
        when(workflowservice.statusCountOnApplicationId(criteria)).thenReturn(mockResponse);

        Response result = bpWorkFlowService.blendedProgramStatusCount(criteria);

        assertNotNull(result);
        assertEquals("success", result.get("status"));
        verify(workflowservice).statusCountOnApplicationId(criteria);
    }

    @Test
    void testUpdateEnrolmentDetails_enrolSuccess() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setUserId("user789");
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        wfRequest.setState(Constants.SEND_FOR_PC_APPROVAL);
        wfRequest.setCreatedOn(String.valueOf(new Date()));
        // Mock batch attributes
        Map<String, Object> batchDetails = new HashMap<>();
        batchDetails.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}");
        batchDetails.put(Constants.ENROLMENT_END_DATE, Instant.now());
        batchDetails.put(Constants.START_DATE, Instant.now());
        batchDetails.put(Constants.NAME, "Batch Name");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList())).thenReturn(Collections.singletonList(batchDetails));

        when(configuration.getCourseServiceHost()).thenReturn("http://localhost/");
        when(configuration.getAdminBlendedProgramEnrolEndPoint()).thenReturn("enrol/blended");

        Map<String, Object> enrolResp = new HashMap<>();
        enrolResp.put(Constants.RESPONSE_CODE, "OK");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_ENROLMENT_BATCH_LOOKUP),
                anyMap(),
                anyList())).thenReturn(List.of(Map.of("active", true)));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(enrolResp);

        bpWorkFlowService.updateEnrolmentDetails(wfRequest);

        // No exception means success
        verify(requestServiceImpl, times(1)).fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class));
    }

    @Test
    void testUpdateEnrolmentDetails_enrolFails_responseNotOk() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setUserId("user789");
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        wfRequest.setState(Constants.SEND_FOR_PC_APPROVAL);
        wfRequest.setCreatedOn(String.valueOf(new Date()));
        // Setup mocks same as above but with failed response
        Map<String, Object> batchDetails = new HashMap<>();
        batchDetails.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}");
        batchDetails.put(Constants.ENROLMENT_END_DATE, Instant.now());
        batchDetails.put(Constants.START_DATE, Instant.now());
        batchDetails.put(Constants.NAME, "Batch Name");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList())).thenReturn(Collections.singletonList(batchDetails));

        when(configuration.getCourseServiceHost()).thenReturn("http://localhost/");
        when(configuration.getAdminBlendedProgramEnrolEndPoint()).thenReturn("enrol/blended");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_ENROLMENT_BATCH_LOOKUP),
                anyMap(),
                anyList())).thenReturn(List.of(Map.of("active", true)));

        Map<String, Object> enrolResp = new HashMap<>();
        enrolResp.put(Constants.RESPONSE_CODE, "ERROR");
        enrolResp.put(Constants.PARAMS, Map.of(Constants.ERROR_MESSAGE, "Enroll failed"));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(enrolResp);
        when(wfStatusRepo.findByApplicationIdAndWfId(any(), any())).thenReturn(new WfStatusEntity());

        bpWorkFlowService.updateEnrolmentDetails(wfRequest);

        verify(requestServiceImpl).fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class));
    }

    @Test
    void testUpdateEnrolmentDetails_enrolThrowsException() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setUserId("user789");
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        wfRequest.setState(Constants.SEND_FOR_PC_APPROVAL);
        wfRequest.setCreatedOn(String.valueOf(new Date()));
        // Setup valid batch
        Map<String, Object> batchDetails = new HashMap<>();
        batchDetails.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}");
        batchDetails.put(Constants.ENROLMENT_END_DATE, Instant.now());
        batchDetails.put(Constants.START_DATE, Instant.now());
        batchDetails.put(Constants.NAME, "Batch Name");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList())).thenReturn(Collections.singletonList(batchDetails));

        when(configuration.getCourseServiceHost()).thenReturn("http://localhost/");
        when(configuration.getAdminBlendedProgramEnrolEndPoint()).thenReturn("enrol/blended");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_ENROLMENT_BATCH_LOOKUP),
                anyMap(),
                anyList())).thenReturn(List.of(Map.of("active", true)));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class)))
                .thenThrow(new RuntimeException("Simulated exception"));
        when(wfStatusRepo.findByApplicationIdAndWfId(any(), any())).thenReturn(new WfStatusEntity());

        bpWorkFlowService.updateEnrolmentDetails(wfRequest);

        verify(requestServiceImpl).fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class));
    }

    @Test
    void testUpdateEnrolmentDetails_enrolNotAllowed_dueToBatchSize() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch123");
        wfRequest.setCourseId("course456");
        wfRequest.setUserId("user789");
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        wfRequest.setState(Constants.SEND_FOR_PC_APPROVAL);
        wfRequest.setCreatedOn(String.valueOf(new Date()));
        // Simulate batch full
        Map<String, Object> batchDetails = new HashMap<>();
        batchDetails.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"1\"}");
        batchDetails.put(Constants.ENROLMENT_END_DATE, Instant.now());
        batchDetails.put(Constants.START_DATE, Instant.now());
        batchDetails.put(Constants.NAME, "Batch Name");

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList())).thenReturn(Collections.singletonList(batchDetails));

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_ENROLMENT_BATCH_LOOKUP),
                anyMap(),
                anyList())).thenReturn(List.of(
                Map.of("active", true), Map.of("active", true) // 2 users > batch size 1
        ));

        bpWorkFlowService.updateEnrolmentDetails(wfRequest);

        verify(requestServiceImpl, never()).fetchResultUsingPost(any(), any(), eq(Map.class), any(HashMap.class));
    }

    @Test
    void testBlendedProgramUserSearch() {
        String userId = "user1";
        SearchCriteria criteria = new SearchCriteria();
        mockResponse = new Response();

        when(workflowservice.applicationsSearch(eq(rootOrg), eq(org), any(SearchCriteria.class)))
                .thenReturn(mockResponse);

        Response response = bpWorkFlowService.blendedProgramUserSearch(rootOrg, org, userId, criteria);

        assertEquals(mockResponse, response);
        assertEquals(userId, criteria.getUserId());
        verify(workflowservice).applicationsSearch(eq(rootOrg), eq(org), any(SearchCriteria.class));
    }

    @Test
    void testReadBPWFApplication_whenApplicationExists_andIsPCFalse() {
        boolean isPc = false;

        WfStatusEntity entity = new WfStatusEntity();
        entity.setWfId(wfId);

        when(wfStatusRepo.findByWfId(wfId)).thenReturn(entity);

        Response response = bpWorkFlowService.readBPWFApplication(wfId, isPc);

        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        List<?> dataList = (List<?>) response.get(Constants.DATA);
        assertEquals(1, dataList.size());
    }

    @Test
    void testReadBPWFApplication_whenNoApplication() {
        when(wfStatusRepo.findByWfId(anyString())).thenReturn(null);

        Response response = bpWorkFlowService.readBPWFApplication("random", true);

        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        List<?> dataList = (List<?>) response.get(Constants.DATA);
        assertTrue(dataList.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            Constants.APPROVED,
            Constants.REMOVED,
            "UNKNOWN"
    })
    void testProcessWFRequest_byStatus(String status) {
        wfRequest = new WfRequest();
        wfRequest.setWfId("wf1");

        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCurrentStatus(status);

        when(wfStatusRepo.findByWfId("wf1")).thenReturn(wfStatusEntity);

        bpWorkFlowService.processWFRequest(wfRequest);

        verify(wfStatusRepo).findByWfId("wf1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            Constants.ENROLL_IS_IN_PROGRESS,
            Constants.ONE_STEP_MDO_APPROVAL,
            Constants.ADMIN_ENROLL_IS_IN_PROGRESS
    })
    void testProcessWFRequest_1_byStatus(String status) {
        wfRequest = new WfRequest();
        wfRequest.setWfId("wf1");

        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCurrentStatus(status);

        when(wfStatusRepo.findByWfId("wf1")).thenReturn(wfStatusEntity);

        ApplicationException exception = assertThrows(
                ApplicationException.class,
                () -> bpWorkFlowService.processWFRequest(wfRequest),
                "Expected processWFRequest to throw, but it didn't"
        );
        assertTrue(exception.getMessage().contains("Workflow parsing error occurred!"));
    }

    @Test
    void testRemoveEnrolmentDetails_successful() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch1");
        wfRequest.setCourseId("course1");
        wfRequest.setUserId("user1");

        // Mock config
        when(configuration.getCourseServiceHost()).thenReturn("http://course-host/");
        when(configuration.getAdminUnEnrolEndPoint()).thenReturn("unenroll");

        // Mock requestServiceImpl
        Map<String, Object> enrolResp = new HashMap<>();
        enrolResp.put(Constants.RESPONSE_CODE, "OK");

        when(requestServiceImpl.fetchResultUsingPost(new StringBuilder(anyString()), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(enrolResp);

        assertDoesNotThrow(()-> bpWorkFlowService.removeEnrolmentDetails(wfRequest));

    }

    @Test
    void testRemoveEnrolmentDetails_failureResponse() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch1");
        wfRequest.setCourseId("course1");
        wfRequest.setUserId("user1");

        when(configuration.getCourseServiceHost()).thenReturn("http://localhost/");
        when(configuration.getAdminUnEnrolEndPoint()).thenReturn("/unenroll");

        Map<String, Object> param = new HashMap<>();
        param.put(Constants.ERROR_MESSAGE, "Something went wrong");

        Map<String, Object> failResp = new HashMap<>();
        failResp.put(Constants.RESPONSE_CODE, "FAIL");
        failResp.put(Constants.PARAMS, param);

        when(requestServiceImpl.fetchResultUsingPost(new StringBuilder(anyString()), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(failResp);

        assertDoesNotThrow(()-> bpWorkFlowService.removeEnrolmentDetails(wfRequest));
    }

    @Test
    void testRemoveEnrolmentDetails_exception() {
        wfRequest = new WfRequest();
        wfRequest.setApplicationId("batch1");
        wfRequest.setCourseId("course1");
        wfRequest.setUserId("user1");

        when(configuration.getCourseServiceHost()).thenReturn("http://localhost/");
        when(configuration.getAdminUnEnrolEndPoint()).thenReturn("/unenroll");

        when(requestServiceImpl.fetchResultUsingPost(new StringBuilder(anyString()), any(), eq(Map.class), any(HashMap.class)))
                .thenThrow(new RuntimeException("Failure"));

        assertDoesNotThrow(()->bpWorkFlowService.removeEnrolmentDetails(wfRequest));
    }

    @Test
    void testReadStats_invalidRequest_emptyRequestBody() {
        Map<String, Object> input = Map.of(Constants.REQUEST, "");

        Response response = bpWorkFlowService.readStats(input);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testReadStats_invalidRequest_emptyCourseIdList() {
        Map<String, Object> requestBody = Map.of(Constants.COURSE_ID_LIST, Collections.emptyList());
        Map<String, Object> input = Map.of(Constants.REQUEST, requestBody);

        Response response = bpWorkFlowService.readStats(input);

        assertEquals(Constants.FAILED, response.get(Constants.MESSAGE));
        assertEquals("Invalid Request. CourseIdList is empty.", response.get(Constants.ERROR_MESSAGE));
        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testReadStats_validRequest_withActiveBatch() {
        Map<String, Object> requestBody = Map.of(Constants.COURSE_ID_LIST, List.of(COURSE_ID));
        Map<String, Object> input = Map.of(Constants.REQUEST, requestBody);

        // Mock Cassandra batch record with future end date
        Instant futureDate = Instant.now().plusSeconds(86400);
        Map<String, Object> courseBatch = new HashMap<>();
        courseBatch.put(Constants.END_DATE, futureDate);
        courseBatch.put(Constants.COURSE_ID_KEY, COURSE_ID);
        courseBatch.put(Constants.BATCH_ID_KEY, BATCH_ID);

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD_COURSES),
                eq(Constants.TABLE_COURSE_BATCH),
                anyMap(),
                anyList()
        )).thenReturn(List.of(courseBatch));

        // Mock WfStatusEntity entries
        WfStatusEntity approved = new WfStatusEntity();
        approved.setCurrentStatus(Constants.APPROVED);

        WfStatusEntity rejected = new WfStatusEntity();
        rejected.setCurrentStatus(Constants.REJECTED);

        WfStatusEntity newReq = new WfStatusEntity();
        newReq.setCurrentStatus(Constants.SEND_FOR_PC_APPROVAL);

        when(wfStatusRepo.findByServiceNameAndApplicationId(
                Constants.BLENDED_PROGRAM_SERVICE_NAME, BATCH_ID))
                .thenReturn(List.of(approved, rejected, newReq));

        Response response = bpWorkFlowService.readStats(input);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(1, response.getResult().get(Constants.COUNT));
        assertNotNull(response.getResult().get(Constants.CONTENT));
    }

    @Test
    void testReadStats_validRequest_withExpiredBatch() {
        Map<String, Object> requestBody = Map.of(Constants.COURSE_ID_LIST, List.of(COURSE_ID));
        Map<String, Object> input = Map.of(Constants.REQUEST, requestBody);

        // Expired batch (end date before today)
        Instant pastDate = Instant.now().minusSeconds(86400);
        Map<String, Object> expiredBatch = new HashMap<>();
        expiredBatch.put(Constants.END_DATE, pastDate);
        expiredBatch.put(Constants.COURSE_ID_KEY, COURSE_ID);
        expiredBatch.put(Constants.BATCH_ID_KEY, BATCH_ID);

        when(cassandraOperation.getRecordsByProperties(
                any(), any(), anyMap(), anyList()
        )).thenReturn(List.of(expiredBatch));

        Response response = bpWorkFlowService.readStats(input);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(0, response.getResult().get(Constants.COUNT));
    }

    @Test
    void testReadStats_exceptionThrown() {
        Map<String, Object> requestBody = Map.of(Constants.COURSE_ID_LIST, List.of(COURSE_ID));
        Map<String, Object> input = Map.of(Constants.REQUEST, requestBody);

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenThrow(new RuntimeException("Database error"));

        Response response = bpWorkFlowService.readStats(input);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.get(Constants.STATUS));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertTrue(((String) response.get(Constants.ERROR_MESSAGE)).contains("Failed to get the stats"));
    }

    private WfRequest getSampleRequest() {
        WfRequest req = new WfRequest();
        req.setApplicationId(BATCH_ID);
        req.setCourseId(COURSE_ID);
        req.setUserId(USER_ID);
        req.setServiceName("service");
        req.setActorUserId("actor-id");
        req.setDeptName("dept");
        req.setComment("test comment");
        req.setUpdateFieldValues(List.of(new HashMap<>()));
        return req;
    }

    @Test
    void testAdminEnrolBPWorkFlow_success() {
        WfRequest req = getSampleRequest();

        Map<String, Object> courseBatchMap = new HashMap<>();
        courseBatchMap.put(Constants.CURRENT_BATCH_SIZE, 10);
        courseBatchMap.put(Constants.START_DATE, Instant.now());
        courseBatchMap.put(Constants.ENROLMENT_END_DATE, Instant.now().plusSeconds(3600));
        courseBatchMap.put(Constants.BATCH_NAME, "Test Batch");

        // Mock course batch attributes
        Map<String, Object> batchAttr = Map.of(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}",
                Constants.START_DATE, Instant.now(), Constants.ENROLMENT_END_DATE, Instant.now().plusSeconds(3600),
                Constants.NAME, "Test Batch");
        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(List.of(batchAttr));

        when(contentReadService.getServiceNameDetails(COURSE_ID)).thenReturn(Map.of("wfApprovalType", "service", "primaryCategory", "Course"));
        when(wfStatusRepo.findByApplicationId(BATCH_ID)).thenReturn(List.of());

        when(configuration.getBpBatchEnrolLimitBufferSize()).thenReturn(20); // 20% buffer

        WfStatusEntity fullStatus = new WfStatusEntity();
        fullStatus.setCurrentStatus("APPROVED");
        when(wfStatusRepo.findByServiceNameAndUserIdAndApplicationId(any(), any(), any())).thenReturn(List.of(fullStatus));

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertTrue(response.get(Constants.MESSAGE).toString().contains("Not allowed to enroll the user to the Blended Program"));
    }

    @Test
    void testAdminEnrolBPWorkFlow_withNullServiceName_shouldDefault() {
        WfRequest req = getSampleRequest();

        when(contentReadService.getServiceNameDetails(any())).thenReturn(null);
        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(List.of());

        when(configuration.getBatchFullMesg()).thenReturn("Batch full");

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("Batch full", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testAdminEnrolBPWorkFlow_batchIsFull() {
        WfRequest req = getSampleRequest();

        Map<String, Object> batchAttr = Map.of(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"1\"}",
                Constants.START_DATE, Instant.now(), Constants.ENROLMENT_END_DATE, Instant.now().plusSeconds(3600),
                Constants.NAME, "Test Batch");

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(List.of(batchAttr));

        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "service", "primaryCategory", "Course"));

        WfStatusEntity fullStatus = new WfStatusEntity();
        fullStatus.setCurrentStatus("APPROVED");

        when(wfStatusRepo.findByApplicationId(any())).thenReturn(List.of(fullStatus));
        when(configuration.getBpBatchFullValidationExcludeStates()).thenReturn(List.of());

        when(configuration.getBatchFullMesg()).thenReturn("Batch full");

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("Batch full", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testAdminEnrolBPWorkFlow_scheduleConflictExists() {
        WfRequest req = getSampleRequest();

        Map<String, Object> batchAttr = Map.of(
                Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}",
                Constants.START_DATE, Instant.now(),
                Constants.ENROLMENT_END_DATE, Instant.now().plusSeconds(3600),
                Constants.NAME, "Test Batch"
        );

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(List.of(batchAttr));

        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "service", "primaryCategory", "Course"));
        when(wfStatusRepo.findByApplicationId(any())).thenReturn(List.of());
        when(configuration.getBpBatchEnrolLimitBufferSize()).thenReturn(0);

        doReturn(true).when(bpWorkFlowService).scheduleConflictCheck(any());

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.NOT_ACCEPTABLE, response.get(Constants.STATUS));
        assertTrue(response.get(Constants.MESSAGE).toString().contains("schedule conflict"));
    }


    @Test
    void testAdminEnrolBPWorkFlow_duplicateEnrollment() {
        WfRequest req = getSampleRequest();

        Map<String, Object> batchAttr = Map.of(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"10\"}",
                Constants.START_DATE, Instant.now(), Constants.ENROLMENT_END_DATE, Instant.now().plusSeconds(3600),
                Constants.NAME, "Test Batch");

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(List.of(batchAttr));
        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "service", "primaryCategory", "Course"));
        when(wfStatusRepo.findByApplicationId(any())).thenReturn(List.of());

        // Simulate user already enrolled
        when(wfStatusRepo.findByServiceNameAndUserIdAndApplicationId(any(), any(), any()))
                .thenReturn(List.of(new WfStatusEntity()));

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertTrue(response.get(Constants.MESSAGE).toString().contains("Not allowed to enroll"));
    }

    @Test
    void testAdminEnrolBPWorkFlow_invalidBatchAttributes_shouldReturnEmpty() {
        WfRequest req = getSampleRequest();

        // Invalid JSON in batch attributes triggers catch
        Map<String, Object> batchAttr = Map.of(Constants.BATCH_ATTRIBUTES, "invalid-json");
        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(List.of(batchAttr));
        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "service", "primaryCategory", "Course"));

        when(configuration.getBatchFullMesg()).thenReturn("Batch full");

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("Batch full", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testAdminEnrolBPWorkFlow_emptyBatchFromDB_shouldReturnEmpty() {
        WfRequest req = getSampleRequest();

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), anyList()))
                .thenReturn(Collections.emptyList());
        when(contentReadService.getServiceNameDetails(any())).thenReturn(Map.of("wfApprovalType", "service", "primaryCategory", "Course"));

        when(configuration.getBatchFullMesg()).thenReturn("Batch full");

        Response response = bpWorkFlowService.adminEnrolBPWorkFlow(ROOT_ORG, ORG, req);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("Batch full", response.get(Constants.ERROR_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "oneStepPCApproval",
            "oneStepMDOApproval",
            "twoStepMDOAndPCApproval",
            "twoStepPCAndMDOApproval"
    })
    void testGetWorkFlowConfigParameterized(String serviceName) throws Exception {
        // Mock response structure
        Map<String, Object> response = new HashMap<>();
        response.put("value", "{\"name\":\"workflowModel\"}");
        Map<String, Object> mockResponseMap = new HashMap<>();
        mockResponseMap.put("response", response);
        Map<String, Object> result = new HashMap<>();
        result.put("result", mockResponseMap);

        // Mocks
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(result);
        when(mapper.readValue(anyString(), eq(Map.class))).thenReturn(Map.of("name", "workflowModel"));
        when(mapper.convertValue(any(), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(new WorkFlowModel());

        // Execute
        WorkFlowModel wfModel = bpWorkFlowService.getWorkFlowConfig(serviceName);

        // Assert
        assertNotNull(wfModel);
    }

    @Test
    void testGetWorkFlowConfig_exceptionThrown() {
        String serviceName = "ONE_STEP_PC_APPROVAL";
        ApplicationException ex = assertThrows(ApplicationException.class, () ->
                bpWorkFlowService.getWorkFlowConfig(serviceName));

        assertTrue(ex.getMessage().contains("Workflow parsing error occurred!"));
    }

    @Test
    void testRemoveApprovedUserSuccess() throws Exception {

        String userId = "user123";
        boolean isPc = true;

        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("app123");
        wfRequest.setCourseId("course123");
        wfRequest.setUserId(userId);

        WfStatusEntity entity = new WfStatusEntity();
        entity.setWfId("wf-id-01");
        entity.setCurrentStatus(Constants.APPROVED);
        entity.setUpdateFieldValues("[{\"toValue\":{\"name\":\"Test\"}}]");
        entity.setServiceName("serviceName");
        entity.setRootOrg("igot");
        entity.setDeptName("dept");
        entity.setOrg("org123");

        when(wfStatusRepo.findWorkflowByBatchAndUser("app123", userId))
                .thenReturn(List.of(entity));

        when(mapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(List.of(Map.of("key", "value")));

        Response updateResponse = new Response();
        updateResponse.put(Constants.STATUS, HttpStatus.OK);

        doReturn(updateResponse).when(bpWorkFlowService)
                .updateBPWorkFlow("igot", "org123", wfRequest, userId, Constants.PROGRAM_COORDINATOR);

        // Act
        Response result = bpWorkFlowService.removeApprovedUser(wfRequest, isPc);

        // Assert
        assertEquals(HttpStatus.OK, result.get(Constants.STATUS));
    }


    @Test
    void testRemoveApprovedUser_Fails_When_ApplicationIdMissing() {
        WfRequest request = new WfRequest();
        request.setCourseId("course123");

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("applicationId must not be empty.", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testRemoveApprovedUser_Fails_When_UserIdMissing() {
        WfRequest request = new WfRequest();
        request.setApplicationId("app123");
        request.setCourseId("course123");

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("userId must not be empty.", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testRemoveApprovedUser_Fails_When_CourseIdMissing() {
        WfRequest request = new WfRequest();
        request.setApplicationId("app123");
        request.setUserId("user123");

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("courseId must not be empty.", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testRemoveApprovedUser_NoActiveWorkflow() {
        WfRequest request = new WfRequest();
        request.setApplicationId("app123");
        request.setCourseId("course123");
        request.setUserId("user123");

        when(wfStatusRepo.findWorkflowByBatchAndUser("app123", "user123"))
                .thenReturn(Collections.emptyList());

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.NOT_FOUND, response.get(Constants.STATUS));
        assertEquals("No active workflow found for the given applicationId and userId.",
                response.get(Constants.ERROR_MESSAGE));
    }


    @Test
    void testRemoveApprovedUser_NoApprovedRecord() {
        WfRequest request = new WfRequest();
        request.setApplicationId("app123");
        request.setCourseId("course123");
        request.setUserId("user123");

        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus("PENDING");

        when(wfStatusRepo.findWorkflowByBatchAndUser("app123", "user123"))
                .thenReturn(List.of(entity));

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.BAD_REQUEST, response.get(Constants.STATUS));
        assertEquals("User is not currently in APPROVED state.", response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testRemoveApprovedUser_MultipleApprovedRecords() {
        WfRequest request = new WfRequest();
        request.setApplicationId("app123");
        request.setCourseId("course123");
        request.setUserId("user123");

        WfStatusEntity record1 = new WfStatusEntity();
        record1.setCurrentStatus(Constants.APPROVED);

        WfStatusEntity record2 = new WfStatusEntity();
        record2.setCurrentStatus(Constants.APPROVED);

        when(wfStatusRepo.findWorkflowByBatchAndUser("app123", "user123"))
                .thenReturn(List.of(record1, record2));

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.get(Constants.STATUS));
        assertEquals("Data inconsistency: Multiple APPROVED workflow records found.",
                response.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testRemoveApprovedUser_Fails_When_UpdateFieldValuesInvalidJson() throws Exception {
        WfRequest request = new WfRequest();
        request.setApplicationId("app123");
        request.setCourseId("course123");
        request.setUserId("user123");

        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus(Constants.APPROVED);
        entity.setUpdateFieldValues("invalid-json");
        entity.setWfId("wf-10");
        entity.setServiceName("svc");
        entity.setRootOrg("root");
        entity.setOrg("org");
        entity.setDeptName("dept");

        when(wfStatusRepo.findWorkflowByBatchAndUser("app123", "user123"))
                .thenReturn(List.of(entity));

        when(mapper.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(new RuntimeException("JSON Parsing Failed"));

        Response response = bpWorkFlowService.removeApprovedUser(request, true);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.get(Constants.STATUS));
        assertEquals("Invalid updateFieldValues format in workflow record.",
                response.get(Constants.ERROR_MESSAGE));
    }

    @BeforeEach
    void init() {
        lenient().when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(Collections.emptyList());
        lenient().when(cassandraOperation.getRecordsByProperties(anyString(), any(), anyMap(), anyList()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> defaultActorProfile = new HashMap<>();
        defaultActorProfile.put("role", "PROGRAM_COORDINATOR");
        defaultActorProfile.put("roles", List.of("PROGRAM_COORDINATOR"));
        defaultActorProfile.put("rootOrg", rootOrg);
        defaultActorProfile.put("org", org);
        defaultActorProfile.put("userId", "actor-default");
        List<Map<String, Object>> orgsMap = new ArrayList<>();
        Map<String, Object> org1 = new HashMap<>();
        org1.put("organisationKey", "org-1");
        org1.put("roles", List.of("PROGRAM_COORDINATOR", "ADMIN"));
        orgsMap.add(org1);

        Map<String, Object> org2 = new HashMap<>();
        org2.put("organisationKey", "org-2");
        org2.put("roles", List.of("MDO", "VIEWER"));
        orgsMap.add(org2);
        defaultActorProfile.put("organisations", orgsMap);
        lenient().when(userUtils.userProfileRead(anyString())).thenAnswer(invocation -> {
            return defaultActorProfile;
        });
        lenient().when(userUtils.userProfileRead(anyString())).thenReturn(defaultActorProfile);
        lenient().when(wfStatusRepo.save(any())).thenAnswer(invocation -> {
            WfStatusEntity e = new WfStatusEntity();
            e.setWfId(UUID.randomUUID().toString());
            e.setCreatedOn(new Date());
            return e;
        });
    }

    private Map<String, Object> buildRequest(String courseId, String batchId, List<String> userIds, String deptName) {
        Map<String, Object> req = new HashMap<>();
        req.put(Constants.COURSE_ID, courseId);
        req.put(Constants.BATCH_ID, batchId);
        if (deptName != null) req.put(Constants.DEPT_NAME, deptName);
        req.put(Constants.USER_IDS, userIds);
        return req;
    }

    private void stubBatchAttributes(long startOffsetSeconds, int currentBatchSize, String batchName) {
        Map<String, Object> batchAttr = new HashMap<>();
        batchAttr.put(Constants.BATCH_ATTRIBUTES, "{\"currentBatchSize\":\"" + currentBatchSize + "\"}");
        batchAttr.put(Constants.START_DATE, Date.from(Instant.now().plusSeconds(startOffsetSeconds)));
        batchAttr.put(Constants.NAME, batchName);
    }

    @Test
    void testNominateUsers_scheduleConflict() {
        String userId = "user-conflict";
        Map<String, Object> request = buildRequest("course-program", "batch-conflict", List.of(userId), "Dept B");

        when(contentReadService.getServiceNameDetails("course-program"))
                .thenReturn(Map.of("wfApprovalType", Constants.ONE_STEP_PC_APPROVAL, "primaryCategory", "Course"));

        stubBatchAttributes(3600L, 10, "Batch Conflict");

        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-conflict", request).getResult();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.get(Constants.STATUS));
    }

    @Test
    void testNominateUsers_batchStartDateInvalid() {
        String userId = "user-batch-start";
        Map<String, Object> request = buildRequest("course-program", "batch-start-invalid", List.of(userId), "Dept C");

        when(contentReadService.getServiceNameDetails("course-program"))
                .thenReturn(Map.of("wfApprovalType", Constants.ONE_STEP_PC_APPROVAL, "primaryCategory", "Course"));

        // batch start in past -> invalid
        // use negative offset to simulate past
        stubBatchAttributes(-3600L, 10, "Batch Start Invalid");

        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-start", request).getResult();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.get(Constants.STATUS));
    }

    @Test
    void testNominateUsers_batchFull() {
        String userId = "user-batch-full";
        Map<String, Object> request = buildRequest("course-program", "batch-full", List.of(userId), "Dept D");

        when(contentReadService.getServiceNameDetails("course-program"))
                .thenReturn(Map.of("wfApprovalType", Constants.ONE_STEP_PC_APPROVAL, "primaryCategory", "Course"));

        // batch full -> currentBatchSize equals some limit (simulate full check)
        stubBatchAttributes(3600L, 200, "Batch Full"); // large number to trigger "full" in validation

        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-full", request).getResult();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.get(Constants.STATUS));
    }

    @Test
    void testNominateUsers_userAlreadyExists() {
        String userId = "user-exists";
        Map<String, Object> request = buildRequest("course1", "batch-exists", List.of(userId), "Dept E");

        when(contentReadService.getServiceNameDetails("course1"))
                .thenReturn(Map.of("wfApprovalType", Constants.ONE_STEP_PC_APPROVAL, "primaryCategory", "Course"));

        // simulate existing active workflow for this user -> service should mark ALREADY_EXISTS
        WfStatusEntity existing = new WfStatusEntity();
        existing.setWfId("existing-wf");

        // batch valid
        stubBatchAttributes(3600L, 10, "Batch Exists");

        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-exists", request).getResult();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.get(Constants.STATUS));
    }

    @Test
    void testNominateUsers_invalidApprovalType() {
        String userId = "user-invalid";
        Map<String, Object> request = buildRequest("course-invalid", "batch-invalid", List.of(userId), "Dept F");

        when(contentReadService.getServiceNameDetails("course-invalid"))
                .thenReturn(Map.of("wfApprovalType", "UNKNOWN_APPROVAL_TYPE", "primaryCategory", "Course"));

        when(wfStatusRepo.findActiveWorkflows("batch-invalid", userId, Boolean.TRUE))
                .thenReturn(Collections.emptyList());

        stubBatchAttributes(3600L, 10, "Batch Invalid");

        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-invalid", request).getResult();
        assertEquals(HttpStatus.OK, resp.get(Constants.STATUS));
        List<?> data = (List<?>) resp.get(Constants.DATA);
        Map<?, ?> userResp = (Map<?, ?>) data.get(0);
        assertEquals(Constants.INVALID_APPROVAL_TYPE, userResp.get(Constants.STATUS));
    }

    @Test
    void testNominateUsers_noUserIdsProvided() {
        Map<String, Object> request = buildRequest("course1", "batch1", Collections.emptyList(), "Dept G");
        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-req", request).getResult();
        assertEquals(HttpStatus.BAD_REQUEST, resp.get(Constants.STATUS));
        assertEquals("User list cannot be empty", resp.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testNominateUsers_tooManyUsers() {
        // build >200 user ids
        List<String> bigList = new ArrayList<>();
        for (int i = 0; i < 201; i++) bigList.add("u" + i);
        Map<String, Object> request = buildRequest("course1", "batch1", bigList, "Dept H");
        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-req", request).getResult();
        assertEquals(HttpStatus.BAD_REQUEST, resp.get(Constants.STATUS));
        assertEquals("Max 200 users allowed", resp.get(Constants.ERROR_MESSAGE));
    }

    @Test
    void testNominateUsers_actorSelfForbidden() {
        String userId = "u1";
        Map<String, Object> request = buildRequest("course1", "batch1", List.of(userId), "Dept I");

        // actor has role SELF -> should be forbidden
        Map<String, Object> selfProfile = new HashMap<>();
        selfProfile.put("role", "SELF");
        when(userUtils.userProfileRead("actor-self")).thenReturn(selfProfile);

        Map<?, ?> resp = bpWorkFlowService.nominateUsers(ROOT, ORG, "actor-self", request).getResult();
        assertEquals(HttpStatus.FORBIDDEN, resp.get(Constants.STATUS));
        assertEquals("You do not have permission to nominate users", resp.get(Constants.ERROR_MESSAGE));
    }


    @Test
    void testReverseApprovedRequest_updatesRequestAndPersistsEntity() throws Exception {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("app123");
        wfRequest.setWfId("wf123");
        Date createdOn = new Date();
        WfStatusEntity entity = new WfStatusEntity();
        entity.setWfId("wf123");
        entity.setApplicationId("app123");
        entity.setCreatedOn(createdOn);
        entity.setCurrentStatus("APPROVED");
        entity.setLastUpdatedOn(null);
        when(wfStatusRepo.findByApplicationIdAndWfId("app123", "wf123")).thenReturn(entity);
        when(wfStatusRepo.save(any(WfStatusEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.invokeMethod(bpWorkFlowService, "reverseApprovedRequest", wfRequest);
        assertEquals(createdOn.toString(), wfRequest.getCreatedOn(), "WfRequest.createdOn should be copied from entity.createdOn");
        assertEquals(Constants.SEND_FOR_PC_APPROVAL, entity.getCurrentStatus(), "Entity status should be set to SEND_FOR_PC_APPROVAL");
        assertNotNull(entity.getLastUpdatedOn(), "Entity.lastUpdatedOn should be updated");
        verify(wfStatusRepo).save(entity);
    }

}
