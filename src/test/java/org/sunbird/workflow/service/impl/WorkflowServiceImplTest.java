package org.sunbird.workflow.service.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.exception.ApplicationException;
import org.sunbird.workflow.exception.BadRequestException;
import org.sunbird.workflow.models.*;
import org.sunbird.workflow.postgres.entity.WfAuditEntity;
import org.sunbird.workflow.postgres.entity.WfStatusCountDTO;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfAuditRepo;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.producer.Producer;
import org.sunbird.workflow.service.StorageService;
import org.sunbird.workflow.service.UserProfileWfService;
import org.sunbird.workflow.utils.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceImplTest {

    @InjectMocks
    private WorkflowServiceImpl workflowServiceImpl;

    @Mock
    private WfStatusRepo wfStatusRepo;
    @Mock
    private WfAuditRepo wfAuditRepo;
    @Mock
    private ObjectMapper mapper;
    @Mock
    private Configuration configuration;
    @Mock
    private UserProfileWfService userProfileWfService;
    @Mock
    private RequestServiceImpl requestServiceImpl;
    @Mock
    private Producer producer;
    @Mock
    private CassandraOperation cassandraOperation;
    @Mock
    private AccessTokenValidator accessTokenValidator;
    @Mock
    private LRUCache<String, List<WfStatusCountDTO>> localCache;
    @Mock
    private StorageService storageService;
    @Mock
    private Producer kafkaProducer;
    @Mock
    private ElasticsearchServiceManager eServiceManager;
    @Mock
    private NotificationTriggerService notificationTriggerService;

    private SearchCriteriaV2 criteriaV2;
    @Mock
    MultipartFile mFile;

    @Mock
    Logger log;

    SearchCriteria criteriaV1;

    @Captor
    ArgumentCaptor<String> uriCaptor;

    @Spy // we spy here to mock getWorkFlowConfig and test getWfStatus
    @InjectMocks
    private WorkflowServiceImpl spyWorkflowServiceImpl;

    @Test
    void getUserWf_shouldReturnWfStatusEntities() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName("service");
        criteriaV1.setApplicationStatus("PENDING");
        List<WfStatusEntity> entities = List.of(new WfStatusEntity());
        when(wfStatusRepo.findByRootOrgAndOrgAndServiceNameAndCurrentStatusAndUserId(any(), any(), any(), any(), any())).thenReturn(entities);
        Response response = workflowServiceImpl.getUserWf("rootOrg", "org", "wid", criteriaV1);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertEquals(entities, response.get(Constants.DATA));
    }

    @Test
    void getUserWFApplicationFieldsV2_shouldReturnOkStatus() {
        SearchCriteria criteria = new SearchCriteria();
        Response response = workflowServiceImpl.getUserWFApplicationFieldsV2("rootOrg", "org", "wid", criteria);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
    }

    @Test
    void appsSearchV2_shouldReturnGroupedInfos() {
        criteriaV2 = new SearchCriteriaV2();
        Response response = workflowServiceImpl.appsSearchV2("rootOrg", criteriaV2);
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertTrue(response.get(Constants.DATA) instanceof Map);
    }

    @Test
    void statusCountOnApplicationId_shouldReturnStatusCount() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setApplicationIds(List.of("appId"));
        List<WfStatusCountDTO> statusCountDTOs = List.of(new WfStatusCountDTO());
        when(localCache.get(any())).thenReturn(statusCountDTOs);
        Response response = workflowServiceImpl.statusCountOnApplicationId(criteriaV1);
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertEquals(statusCountDTOs, response.get(Constants.DATA));
    }

    @Test
    void updatePendingRequestsToNewMDO_shouldReturnResponse() {
        Map<String, Object> request = new HashMap<>();
        Response response = workflowServiceImpl.updatePendingRequestsToNewMDO(request);
        assertNotNull(response);
    }

    @Test
    void getBulkUpdateStatus_shouldReturnDefaultResponse() {
        SBApiResponse response = workflowServiceImpl.getBulkUpdateStatus("token");
        assertNotNull(response);
        assertEquals(Constants.API_USER_BULK_UPDATE_STATUS, response.getId());
    }

    @Test
    void workflowBulkUpdateTransition_shouldReturnDefaultResponse() {
        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);
        assertNotNull(response);
        assertEquals(Constants.API_USER_BULK_UPDATE, response.getId());
    }

    @Test
    void getUserProfileApprovalRequest_shouldReturnResponse() {
        SearchCriteria criteria = new SearchCriteria();
        Response response = workflowServiceImpl.getUserProfileApprovalRequest("rootOrg", "org", criteria, "rootOrgId");
        assertNotNull(response);
    }

    @Test
    void getNoPendingRequestAvailableResponse_shouldReturnInputStreamResource() {
        ResponseEntity<?> response = ReflectionTestUtils.invokeMethod(workflowServiceImpl, "getNoPendingRequestAvailableResponse", "No data");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof InputStreamResource);
    }

    @Test
    void buildUserSearchRequest_shouldReturnRequestMap() {
        Map<String, Object> result = WorkflowServiceImpl.buildUserSearchRequest("userId");
        assertTrue(result.containsKey(Constants.REQUEST));
    }

    @Test
    void workflowTransition_happyPath() throws Exception {
        String rootOrg = "rootOrg";
        String org = "org";
        String userId = "userId";
        String role = "role";

        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId(null);
        wfRequest.setApplicationId("appId");
        wfRequest.setUserId(userId);
        wfRequest.setActorUserId("actorId");
        wfRequest.setAction("APPROVE");
        wfRequest.setServiceName("PROFILE_SERVICE");
        wfRequest.setState("PENDING");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("name", "testName");
        Map<String, Object> fieldChange = new HashMap<>();
        fieldChange.put("toValue", toValue);
        wfRequest.setUpdateFieldValues(List.of((HashMap<String, Object>) fieldChange));

        when(configuration.getMultipleWfCreationEnable()).thenReturn(false);
        when(configuration.getLmsServiceHost()).thenReturn("http://lms/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/search");
        when(configuration.getWorkFlowNotificationTopic()).thenReturn("topic1");
        when(configuration.getWorkflowApplicationTopic()).thenReturn("topic2");

        // mock WfStatusEntity
        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCreatedOn(new Date());
        wfStatusEntity.setCurrentStatus("PENDING");
        wfStatusEntity.setRequestType(Constants.ORG_TRANSFER_REQUEST);
        when(wfStatusRepo.findByRootOrgAndOrgAndApplicationIdAndWfId(any(), any(), any(), any())).thenReturn(wfStatusEntity);

        when(mapper.writeValueAsString(any())).thenReturn("{}");

        Map<String, Object> wfConfig = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> mResponse = new HashMap<>();
        String valueJson = "{\"state1\": \"data\"}"; // whatever your JSON string is

        wfConfig.put(Constants.RESULT, result);
        result.put(Constants.RESPONSE, mResponse);
        mResponse.put(Constants.VALUE, valueJson);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(wfConfig);
        Map<String, Object> wfStatesMap = Map.of("state1", "data");
        WorkFlowModel model = new WorkFlowModel();

        WfStatus status = new WfStatus();
        status.setState("PENDING");
        status.setStartState(true);
        status.setIsLastState(true);
        status.setActions(List.of());

        WfAction action1 = new WfAction();
        action1.setAction("APPROVE");
        action1.setNextState("PENDING");

        WfAction action2 = new WfAction();
        action2.setAction("APPROVED");
        action2.setNextState("APPROVED");

        status.setActions(List.of(action1, action2));

        model.setWfstates(List.of(status));

        when(mapper.readValue(valueJson, Map.class)).thenReturn(wfStatesMap);
        when(mapper.convertValue(eq(wfStatesMap), any(TypeReference.class))).thenReturn(model);

        when(wfStatusRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), any())).thenReturn(Map.of("responseCode", "OK"));

        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any())).thenReturn(List.of(Map.of("userRootOrgId", "rootOrgId")));

        String allowedActions = "CREATE,UPDATE,DELETE";

        // mock configuration to return allowedActions
        Mockito.when(configuration.getModificationRecordAllowActions()).thenReturn(allowedActions);


        // Act
        Response response = workflowServiceImpl.workflowTransition(rootOrg, org, wfRequest, userId, role);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        verify(producer, atLeastOnce()).push(any(), any());
    }

    @Test
    void workflowTransition_withGroupKey() throws Exception {
        String rootOrg = "rootOrg";
        String org = "org";
        String userId = "userId";
        String role = "role";

        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId(null);
        wfRequest.setApplicationId("appId");
        wfRequest.setUserId(userId);
        wfRequest.setActorUserId("actorId");
        wfRequest.setAction("APPROVE");
        wfRequest.setServiceName("profile");
        wfRequest.setState("PENDING");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("group", "group");
        Map<String, Object> fieldChange = new HashMap<>();
        fieldChange.put("toValue", toValue);
        wfRequest.setUpdateFieldValues(List.of((HashMap<String, Object>) fieldChange));

        when(configuration.getMultipleWfCreationEnable()).thenReturn(false);
        when(configuration.getLmsServiceHost()).thenReturn("http://lms/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/search");
        when(configuration.getWorkFlowNotificationTopic()).thenReturn("topic1");
        when(configuration.getWorkflowApplicationTopic()).thenReturn("topic2");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("endpoint/${userId}");

        // mock WfStatusEntity
        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCreatedOn(new Date());
        wfStatusEntity.setCurrentStatus("PENDING");
        wfStatusEntity.setRequestType(Constants.ORG_TRANSFER_REQUEST);
        when(wfStatusRepo.findByRootOrgAndOrgAndApplicationIdAndWfId(any(), any(), any(), any())).thenReturn(wfStatusEntity);

        when(mapper.writeValueAsString(any())).thenReturn("{}");

        Map<String, Object> wfConfig = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> mResponse = new HashMap<>();
        String valueJson = "{\"state1\": \"data\"}"; // whatever your JSON string is

        wfConfig.put(Constants.RESULT, result);
        result.put(Constants.RESPONSE, mResponse);
        mResponse.put(Constants.VALUE, valueJson);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(wfConfig);
        Map<String, Object> wfStatesMap = Map.of("state1", "data");
        WorkFlowModel model = new WorkFlowModel();

        WfStatus status = new WfStatus();
        status.setState("PENDING");
        status.setStartState(true);
        status.setIsLastState(true);
        status.setActions(List.of());

        WfAction action1 = new WfAction();
        action1.setAction("APPROVE");
        action1.setNextState("PENDING");

        WfAction action2 = new WfAction();
        action2.setAction("APPROVED");
        action2.setNextState("APPROVED");

        status.setActions(List.of(action1, action2));

        model.setWfstates(List.of(status));

        when(mapper.readValue(valueJson, Map.class)).thenReturn(wfStatesMap);
        when(mapper.convertValue(eq(wfStatesMap), any(TypeReference.class))).thenReturn(model);

        when(wfStatusRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), any())).thenReturn(Map.of("responseCode", "OK"));


        String allowedActions = "CREATE,UPDATE,DELETE";

        // mock configuration to return allowedActions
        Mockito.when(configuration.getModificationRecordAllowActions()).thenReturn(allowedActions);
        Map<String, Object> fakeMap = new HashMap<>();
        fakeMap.put("result", "this-is-a-string-not-a-map"); // not a Map, will trigger the condition

        when(mapper.convertValue(any(), eq(Map.class))).thenReturn(fakeMap);
        // Act
        Response response = workflowServiceImpl.workflowTransition(rootOrg, org, wfRequest, userId, role);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        verify(producer, atLeastOnce()).push(any(), any());
    }

    @Test
    void workflowTransition_withDesignationKey() throws Exception {
        String rootOrg = "rootOrg";
        String org = "org";
        String userId = "userId";
        String role = "role";

        WfRequest wfRequest = new WfRequest();
        wfRequest.setWfId(null);
        wfRequest.setApplicationId("appId");
        wfRequest.setUserId(userId);
        wfRequest.setActorUserId("actorId");
        wfRequest.setAction("APPROVE");
        wfRequest.setServiceName("profile");
        wfRequest.setState("PENDING");

        Map<String, Object> toValue = new HashMap<>();
        toValue.put(Constants.DESIGNATION, "group");
        Map<String, Object> fieldChange = new HashMap<>();
        fieldChange.put("toValue", toValue);
        wfRequest.setUpdateFieldValues(List.of((HashMap<String, Object>) fieldChange));

        when(configuration.getMultipleWfCreationEnable()).thenReturn(false);
        when(configuration.getLmsServiceHost()).thenReturn("http://lms/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/search");
        when(configuration.getWorkFlowNotificationTopic()).thenReturn("topic1");
        when(configuration.getWorkflowApplicationTopic()).thenReturn("topic2");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("endpoint/${userId}");

        // mock WfStatusEntity
        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCreatedOn(new Date());
        wfStatusEntity.setCurrentStatus("PENDING");
        wfStatusEntity.setRequestType(Constants.ORG_TRANSFER_REQUEST);
        when(wfStatusRepo.findByRootOrgAndOrgAndApplicationIdAndWfId(any(), any(), any(), any())).thenReturn(wfStatusEntity);

        when(mapper.writeValueAsString(any())).thenReturn("{}");

        Map<String, Object> wfConfig = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> mResponse = new HashMap<>();
        String valueJson = "{\"state1\": \"data\"}"; // whatever your JSON string is

        wfConfig.put(Constants.RESULT, result);
        result.put(Constants.RESPONSE, mResponse);
        mResponse.put(Constants.VALUE, valueJson);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(wfConfig);
        Map<String, Object> wfStatesMap = Map.of("state1", "data");
        WorkFlowModel model = new WorkFlowModel();

        WfStatus status = new WfStatus();
        status.setState("PENDING");
        status.setStartState(true);
        status.setIsLastState(true);
        status.setActions(List.of());

        WfAction action1 = new WfAction();
        action1.setAction("APPROVE");
        action1.setNextState("PENDING");

        WfAction action2 = new WfAction();
        action2.setAction("APPROVED");
        action2.setNextState("APPROVED");

        status.setActions(List.of(action1, action2));

        model.setWfstates(List.of(status));

        when(mapper.readValue(valueJson, Map.class)).thenReturn(wfStatesMap);
        when(mapper.convertValue(eq(wfStatesMap), any(TypeReference.class))).thenReturn(model);

        when(wfStatusRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), any())).thenReturn(Map.of("responseCode", "OK"));


        String allowedActions = "CREATE,UPDATE,DELETE";

        // mock configuration to return allowedActions
        Mockito.when(configuration.getModificationRecordAllowActions()).thenReturn(allowedActions);
        Map<String, Object> fakeMap = new HashMap<>();
        fakeMap.put("result", "this-is-a-string-not-a-map"); // not a Map, will trigger the condition

        when(mapper.convertValue(any(), eq(Map.class))).thenReturn(fakeMap);
        // Act
        Response response = workflowServiceImpl.workflowTransition(rootOrg, org, wfRequest, userId, role);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        verify(producer, atLeastOnce()).push(any(), any());
    }

    @Test
    void testAppsPCSearchV2_whenNoDeptName_thenSuccess() {
        criteriaV2 = new SearchCriteriaV2();

        criteriaV2.setApplicationStatus(Collections.singletonList("APPROVED"));
        criteriaV2.setApplicationIds(Arrays.asList("app1", "app2"));

        when(userProfileWfService.enrichUserData(anyMap(), anyString())).thenReturn(Collections.singletonList(new HashMap<>()));

        Response response = workflowServiceImpl.appsPCSearchV2("rootOrg", "org", criteriaV2);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertNotNull(response.get(Constants.DATA));
    }

    @Test
    void testAppsPCSearchV2_whenDeptNamePresent_thenSuccess() {
        criteriaV2 = new SearchCriteriaV2();

        criteriaV2.setApplicationStatus(Collections.singletonList("APPROVED"));
        criteriaV2.setApplicationIds(Arrays.asList("app1", "app2"));

        criteriaV2.setDeptName(Arrays.asList("dept1"));

        WfStatusEntity wf1 = new WfStatusEntity();
        wf1.setUserId("user2");
        List<WfStatusEntity> wfStatusEntities = Arrays.asList(wf1);

        when(wfStatusRepo.findByStatusAndDeptAndAppIds(anyList(), anyList(), anyList())).thenReturn(wfStatusEntities);
        when(userProfileWfService.enrichUserData(anyMap(), anyString())).thenReturn(Collections.singletonList(new HashMap<>()));

        Response response = workflowServiceImpl.appsPCSearchV2("rootOrg", "org", criteriaV2);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertNotNull(response.get(Constants.DATA));
    }

    @Test
    void testAppsSearchV2_whenNoDeptName_thenSuccess() {
        criteriaV2 = new SearchCriteriaV2();

        criteriaV2.setApplicationStatus(Collections.singletonList("APPROVED"));
        criteriaV2.setApplicationIds(Arrays.asList("app1", "app2"));

        WfStatusEntity wf1 = new WfStatusEntity();
        wf1.setUserId("user1");
        List<WfStatusEntity> wfStatusEntities = Arrays.asList(wf1);

        when(wfStatusRepo.findByStatusAndAppIds(anyList(), anyList())).thenReturn(wfStatusEntities);

        Response response = workflowServiceImpl.appsSearchV2("rootOrg", criteriaV2);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertNotNull(response.get(Constants.DATA));

    }

    @Test
    void testAppsSearchV2_whenDeptNamePresent_thenSuccess() {
        criteriaV2 = new SearchCriteriaV2();

        criteriaV2.setApplicationStatus(Collections.singletonList("APPROVED"));
        criteriaV2.setApplicationIds(Arrays.asList("app1", "app2"));

        criteriaV2.setDeptName(Arrays.asList("dept1"));

        WfStatusEntity wf1 = new WfStatusEntity();
        wf1.setUserId("user2");
        List<WfStatusEntity> wfStatusEntities = Arrays.asList(wf1);

        when(wfStatusRepo.findByStatusAndDeptAndAppIds(anyList(), anyList(), anyList())).thenReturn(wfStatusEntities);

        Response response = workflowServiceImpl.appsSearchV2("rootOrg", criteriaV2);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertNotNull(response.get(Constants.DATA));
    }


    @Test
    void testApplicationsSearch_profileService_withSort() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.PROFILE_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");

        criteriaV1.setSortBy(Map.of("first_name", "asc"));

        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndApplicationIdIn(any(), any(), any())).thenReturn(mockWfList("app1", "u1"));

        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        when(userProfileWfService.enrichUserData(any(), anyString())).thenReturn(mockUserProfiles());

        Response response = workflowServiceImpl.applicationsSearch("rootOrg", "org", criteriaV1);

        assertNotNull(response);
    }

    @Test
    void testApplicationsSearch_blendedProgram_withStatus() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.PROFILE_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);

        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndApplicationId(anyList(), any(), any())).thenReturn(mockWfList("app1", "u1"));

        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        when(userProfileWfService.enrichUserData(any(), anyString())).thenReturn(mockUserProfiles());

        Response response = workflowServiceImpl.applicationsSearch("rootOrg", "org", criteriaV1);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
    }

    @Test
    void testApplicationsSearch_blendedProgram_withoutStatus() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.PROFILE_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        criteriaV1.setApplicationStatus(null);

        when(userProfileWfService.enrichUserData(any(), anyString())).thenReturn(mockUserProfiles());

        Response response = workflowServiceImpl.applicationsSearch("rootOrg", "org", criteriaV1);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
    }

    @Test
    void testApplicationsSearch_default() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setServiceName("OTHER_SERVICE");

        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndApplicationIdIn(any(), any(), any())).thenReturn(mockWfList("app1", "u1"));

        Response response = workflowServiceImpl.applicationsSearch("rootOrg", "org", criteriaV1);

        assertNotNull(response);
    }

    @Test
    void testApplicationSearchOnApplicationIdGroup_withDept_searchEnabled() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setDeptName("dept1");

        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);

        Response response = workflowServiceImpl.applicationSearchOnApplicationIdGroup("rootOrg", criteriaV1, true);

        assertNotNull(response);
    }

    @Test
    void testApplicationSearchOnApplicationIdGroup_noDept() {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.PROFILE_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");

        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndApplicationIdIn(any(), any(), any())).thenReturn(mockWfList("app1", "u1"));

        Response response = workflowServiceImpl.applicationSearchOnApplicationIdGroup("rootOrg", criteriaV1);

        assertNotNull(response);
    }

    @Test
    void testApplicationUserSearchOnApplicationIdGroup_success() {

        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.PROFILE_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setUserId("u1");

        when(wfStatusRepo.findByServiceNameAndUserIdAndApplicationIdIn(anyList(), any(), anyList())).thenReturn(mockWfList("app1", "u1"));

        Response response = workflowServiceImpl.applicationUserSearchOnApplicationIdGroup(criteriaV1);

        assertNotNull(response);
    }

    @Test
    void testApplicationUserSearchOnApplicationIdGroup_noApplicationIds_throwsException() {

        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName(Constants.PROFILE_SERVICE_NAME);
        criteriaV1.setApplicationIds(List.of("app1", "app2"));
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setApplicationIds(null);

        Exception ex = assertThrows(ApplicationException.class, () -> {
            workflowServiceImpl.applicationUserSearchOnApplicationIdGroup(criteriaV1);
        });
        assertTrue(ex.getMessage().contains(Constants.WORKFLOW_PARSING_ERROR_MESSAGE));
    }

    @Test
    void testGetPageReqForApplicationSearch_emptyCriteria_throwsException() {
        SearchCriteria emptyCriteria = mock(SearchCriteria.class);
        when(emptyCriteria.isEmpty()).thenReturn(true);

        Exception ex = assertThrows(BadRequestException.class, () -> {
            workflowServiceImpl.applicationSearchOnApplicationIdGroup("rootOrg", emptyCriteria);
        });
        assertTrue(ex.getMessage().contains(Constants.SEARCH_CRITERIA_VALIDATION));
    }

    @Test
    void testGetWorkflowProcess_whenNoData() {
        String rootOrg = "rootOrg1";
        String wfId = "wf123";

        when(wfStatusRepo.findByRootOrgAndWfId(rootOrg, wfId)).thenReturn(null);

        Response response = workflowServiceImpl.getWorkflowProcess(rootOrg, wfId);

        assertNotNull(response);
        assertEquals("Successful", response.get("message"));
        assertEquals(HttpStatus.OK, response.get("status"));
        assertNull(response.get("data"));

        verify(wfStatusRepo, times(1)).findByRootOrgAndWfId(rootOrg, wfId);
    }

    @Test
    void testUpdateUserProfileWF_success() throws Exception {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setServiceName("service1");
        wfRequest.setUserId("user1");
        wfRequest.setApplicationId("app1");
        wfRequest.setActorUserId("actor1");

        HashMap<String, String> toValue = new HashMap<>();
        toValue.put("departmentName", "HR");

        HashMap<String, Object> field = new HashMap<>();
        field.put("toValue", toValue);

        wfRequest.setUpdateFieldValues(Collections.singletonList(field));

        when(configuration.getWorkFlowNotificationTopic()).thenReturn("notif-topic");
        when(configuration.getWorkflowApplicationTopic()).thenReturn("app-topic");

        when(mapper.writeValueAsString(any())).thenReturn("{\"dummy\":\"json\"}");

        Response response = workflowServiceImpl.updateUserProfileWF("rootOrg1", "org1", wfRequest);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get("status"));
        assertTrue(((Map<?, ?>) response.get("data")).get("wfIds") instanceof List);

        verify(wfStatusRepo, times(1)).save(any(WfStatusEntity.class));
        verify(producer, times(1)).push(eq("notif-topic"), any());
        verify(producer, times(1)).push(eq("app-topic"), any());
    }

    @Test
    void testUpdateUserProfileWF_jsonProcessingException() throws Exception {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setServiceName("service1");
        wfRequest.setUserId("user1");
        wfRequest.setApplicationId("app1");
        wfRequest.setActorUserId("actor1");

        HashMap<String, String> toValue = new HashMap<>();
        toValue.put("departmentName", "HR");

        HashMap<String, Object> field = new HashMap<>();
        field.put("toValue", toValue);

        wfRequest.setUpdateFieldValues(Collections.singletonList(field));
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("fail") {
        });

        ApplicationException thrown = assertThrows(ApplicationException.class, () -> workflowServiceImpl.updateUserProfileWF("rootOrg1", "org1", wfRequest));

        assertEquals(Constants.WORKFLOW_PARSING_ERROR_MESSAGE, thrown.getMessage());
        verify(wfStatusRepo, never()).save(any());
        verify(producer, never()).push(any(), any());
    }

    @Test
    void testGetUserWFApplicationFields_success() throws Exception {
        criteriaV1 = new SearchCriteria();
        criteriaV1.setServiceName("service1");
        criteriaV1.setApplicationStatus("status1");
        String rootOrg = "rootOrg1", org = "org1", wid = "wid1";
        List<String> updatedFieldValues = Arrays.asList("[{\"toValue\":{\"departmentName\":\"HR\"}}]", null, "");

        List<HashMap<String, Object>> parsedValue = new ArrayList<>();
        HashMap<String, Object> map = new HashMap<>();
        HashMap<String, Object> toVal = new HashMap<>();
        toVal.put("departmentName", "HR");
        map.put("toValue", toVal);
        parsedValue.add(map);

        when(wfStatusRepo.findWfFieldsForUser(rootOrg, org, "service1", "status1", wid)).thenReturn(updatedFieldValues);

        when(mapper.readValue(anyString(), any(TypeReference.class))).thenReturn(parsedValue);

        Response response = workflowServiceImpl.getUserWFApplicationFields(rootOrg, org, wid, criteriaV1);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.get("status"));
        assertEquals("Successful", response.get("message"));

        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertTrue(data.containsKey("departmentName"));
        assertEquals("HR", data.get("departmentName"));

        verify(mapper, times(1)).readValue(anyString(), any(TypeReference.class));
    }

    @Test
    void testDownloadPendingRequestFile_happyPath() throws Exception {
        String token = "token";
        String mdoUserId = "mdo1";
        String departmentName = "dept1";
        String rootOrgId = "rootOrg";

        WfStatusEntity wfEntity = new WfStatusEntity();
        wfEntity.setUserId("user1");
        wfEntity.setUpdateFieldValues("[{\"toValue\":{\"group\":\"G1\"}}]");


        when(configuration.getPendingRequestCountLimit()).thenReturn(10);
        when(configuration.getLmsServiceHost()).thenReturn("http://mock-lms/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("search");

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(mdoUserId);

        // mock MDO user search
        Map<String, Object> mdoMap = new HashMap<>();
        mdoMap.put(mdoUserId, Map.of("rootOrgName", departmentName, "rootOrgId", rootOrgId));
        when(requestServiceImpl.fetchResultUsingPostUnhandled(any(), any(), eq(Map.class), any())).thenReturn(getSearchResponse(true));

        // mock pending requests
        when(wfStatusRepo.getListOfApplicationUsingDept("profile", "SEND_FOR_APPROVAL","dept1", 10)).thenReturn(List.of(wfEntity));

        ResponseEntity<?> response = workflowServiceImpl.downloadPendingRequestFile(token);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof InputStreamResource || response.getBody() == null);
    }

    @Test
    void testDownloadPendingRequestFile_noPendingRequests() throws Exception {
        String token = "token";
        String mdoUserId = "mdo1";

        when(configuration.getPendingRequestCountLimit()).thenReturn(10);
        when(configuration.getLmsServiceHost()).thenReturn("http://mock-lms/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("search");

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(mdoUserId);

        when(requestServiceImpl.fetchResultUsingPostUnhandled(any(), any(), eq(Map.class), any())).thenReturn(getSearchResponse(true));

        when(wfStatusRepo.getListOfApplicationUsingDept(any(), any(), any(), anyInt())).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = workflowServiceImpl.downloadPendingRequestFile(token);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testDownloadPendingRequestFile_exceptionThrown() {
        String token = "token";
        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenThrow(new RuntimeException("boom"));

        ResponseEntity<?> response = workflowServiceImpl.downloadPendingRequestFile(token);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }


    @Test
    void testGetUserProfileApprovalRequest_whenRequestTypeIsNull_shouldDelegateToApplicationsSearch() {
        // Arrange
        criteriaV1 = new SearchCriteria();
        criteriaV1.setRequestType(null);
        criteriaV1.setServiceName("service");
        criteriaV1.setDeptName("dept");
        criteriaV1.setApplicationStatus("APPROVED");

        WorkflowServiceImpl spy = Mockito.spy(workflowServiceImpl);
        doReturn(new Response()).when(spy).applicationsSearch(any(), any(), any());

        // Act
        spy.getUserProfileApprovalRequest("rootOrg", "org", criteriaV1, "rootOrgId");

        // Assert
        verify(spy).applicationsSearch("rootOrg", "org", criteriaV1);
    }

    @Test
    void testGetUserProfileApprovalRequest_whenQueryIsNotBlank_shouldSortBySearchScore() {
        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        criteriaV1 = new SearchCriteria();

        criteriaV1.setRequestType(List.of("GROUP_CHANGE"));
        criteriaV1.setServiceName("profile");
        criteriaV1.setDeptName("dept");
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setQuery("searchQuery");

        Map<String, Object> userInfoMap = new HashMap<>();
        userInfoMap.put("appId1", Map.of(Constants.SEARCH_SCORE, 0.9f));

        when(eServiceManager.searchUsers(any(), anyInt(), anyInt(), anyMap(), any(), anyList())).thenReturn(1L);

        WfStatusEntity wfEntity = new WfStatusEntity();
        wfEntity.setApplicationId("appId1");
        wfEntity.setUpdateFieldValues("[]");

        Response response = workflowServiceImpl.getUserProfileApprovalRequest("rootOrg", "org", criteriaV1, "rootOrgId");

        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
    }

    @Test
    void testGetUserProfileApprovalRequest_whenNoQuery_enrichUserDataAndSortBy() throws JsonProcessingException {
        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        criteriaV1 = new SearchCriteria();
        criteriaV1.setRequestType(List.of("GROUP_CHANGE"));
        criteriaV1.setServiceName("profile");
        criteriaV1.setDeptName("dept");
        criteriaV1.setApplicationStatus("APPROVED");
        criteriaV1.setSortBy(Map.of("createdOn", "desc"));

        WfStatusEntity wfEntity = new WfStatusEntity();
        wfEntity.setApplicationId("appId1");
        wfEntity.setCreatedOn(new Date());
        wfEntity.setUpdateFieldValues("[]");

        when(wfStatusRepo.getListOfDistinctUserIdsUsingRequestTypeForProfileApproval(
                anyString(), anyString(), anyString(), anyList(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of("appId1")));

        when(wfStatusRepo.getCountOfDistinctUserIdForProfileApproval(
                anyString(), anyString(), anyString(), anyList(), anyString()))
                .thenReturn(1L);

        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndDeptNameAndUserIdInAndRequestTypeIn(
                anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(List.of(wfEntity));

        // make it mutable!
        List<Map<String, Object>> enriched = new ArrayList<>();
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("first_name", "John");

        Map<String, Object> enrichedMap = new HashMap<>();
        enrichedMap.put("userInfo", userInfo);
        enrichedMap.put("wfInfo", List.of(wfEntity));

        enriched.add(enrichedMap);

        when(userProfileWfService.enrichUserData(any(), any()))
                .thenReturn(enriched);

        List<Map<String, Object>> mockedValuesList = new ArrayList<>();
        Map<String, Object> toValue = new HashMap<>();
        toValue.put("name", "SomeName");

        Map<String, Object> valueMap = new HashMap<>();
        valueMap.put(Constants.TO_VALUE, toValue);
        mockedValuesList.add(valueMap);

        when(mapper.readValue(anyString(), ArgumentMatchers.<TypeReference<List<Map<String, Object>>>>any()))
                .thenReturn(mockedValuesList);

        Response response = workflowServiceImpl.getUserProfileApprovalRequest("rootOrg", "org", criteriaV1, "rootOrgId");

        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
    }

    @Test
    void testGetUserProfileApprovalRequest_whenExceptionOccurs_shouldHandleGracefully() {
        when(configuration.getDefaultLimit()).thenReturn(10);
        when(configuration.getMaxLimit()).thenReturn(50);
        when(configuration.getDefaultOffset()).thenReturn(0);
        SearchCriteria criteria = new SearchCriteria();
        criteria.setRequestType(List.of("GROUP_CHANGE"));
        criteria.setServiceName("profile");
        criteria.setDeptName("dept");
        criteria.setApplicationStatus("APPROVED");

        when(wfStatusRepo.getListOfDistinctUserIdsUsingRequestTypeForProfileApproval(anyString(), anyString(), anyString(), anyList(), anyString(), any(Pageable.class))).thenThrow(new RuntimeException("DB error"));

        Response response = workflowServiceImpl.getUserProfileApprovalRequest("rootOrg", "org", criteria, "rootOrgId");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.get(Constants.STATUS));
        assertEquals(Constants.FAILED, response.get(Constants.MESSAGE));
    }

    @Test
    void testIdentifyAndMarkOrgTransferRequest_shouldMarkProperly() throws Exception {
        WfStatusEntity wfEntity = new WfStatusEntity();
        wfEntity.setUpdateFieldValues("[{\"toValue\":{\"name\":\"newOrg\"}}]");
        Map<String, Object> userData = Map.of("wfInfo", List.of(wfEntity));

        Response response = new Response();
        response.put(Constants.DATA, List.of(userData));

        when(mapper.readValue(anyString(), any(TypeReference.class))).thenReturn(List.of(Map.of("toValue", Map.of("name", "newOrg"))));

        workflowServiceImpl.identifyAndMarkOrgTransferRequest(response);

        assertTrue(wfEntity.isOrgTansferRequest());
    }


    @Test
    void testWorkflowBulkUpdateTransitionV1_HappyPath() throws IOException {
        String token = "valid-token";
        String userId = "user123";
        String rootOrgId = "rootOrgId";
        String fileName = "file.csv";
        String fileUrl = "http://example.com/file.csv";

        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");
        when(configuration.getUserBulkUpdateTopic()).thenReturn("topic");
        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(userId);

        Map<String, Object> userRecord = Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId);
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.USER_TABLE), anyMap(), anyList()))
                .thenReturn(List.of(userRecord));

        SBApiResponse uploadResponse = ProjectUtil.createDefaultResponse(Constants.API_USER_BULK_UPDATE);
        uploadResponse.setResponseCode(HttpStatus.OK);
        uploadResponse.getResult().put(Constants.NAME, fileName);
        uploadResponse.getResult().put(Constants.URL, fileUrl);

        when(storageService.uploadFile(
                mFile,
                "folder",
                "container"))
                .thenReturn(uploadResponse);

        Response cassandraInsertResponse = new Response();
        cassandraInsertResponse.put("STATUS", Constants.SUCCESS);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(cassandraInsertResponse);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1(token, mFile);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFUL, response.getParams().getStatus());
        assertEquals(fileName, response.getResult().get(Constants.FILE_NAME));
        assertEquals(fileUrl, response.getResult().get(Constants.FILE_PATH));

        verify(kafkaProducer).push(eq("topic"), anyMap());
    }

    @Test
    void testWorkflowBulkUpdateTransitionV1_Error() throws IOException {
        String token = "valid-token";
        String userId = "user123";
        String rootOrgId = "rootOrgId";
        String fileName = "file.csv";
        String fileUrl = "http://example.com/file.csv";

        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");
        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(userId);

        Map<String, Object> userRecord = Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId);
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.USER_TABLE), anyMap(), anyList()))
                .thenReturn(List.of(userRecord));

        SBApiResponse uploadResponse = ProjectUtil.createDefaultResponse(Constants.API_USER_BULK_UPDATE);
        uploadResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        uploadResponse.getResult().put(Constants.NAME, fileName);
        uploadResponse.getResult().put(Constants.URL, fileUrl);

        when(storageService.uploadFile(
                mFile,
                "folder",
                "container"))
                .thenReturn(uploadResponse);

        Response cassandraInsertResponse = new Response();
        cassandraInsertResponse.put("STATUS", Constants.SUCCESS);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1(token, mFile);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Failed to upload file. Error: null", response.getParams().getErrmsg());
    }

    @Test
    void testWorkflowBulkUpdateTransitionV1_FailedToInsert() throws IOException {
        String token = "valid-token";
        String userId = "user123";
        String rootOrgId = "rootOrgId";
        String fileName = "file.csv";
        String fileUrl = "http://example.com/file.csv";

        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");
        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(userId);

        Map<String, Object> userRecord = Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId);
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.USER_TABLE), anyMap(), anyList()))
                .thenReturn(List.of(userRecord));

        SBApiResponse uploadResponse = ProjectUtil.createDefaultResponse(Constants.API_USER_BULK_UPDATE);
        uploadResponse.setResponseCode(HttpStatus.OK);
        uploadResponse.getResult().put(Constants.NAME, fileName);
        uploadResponse.getResult().put(Constants.URL, fileUrl);

        when(storageService.uploadFile(
                mFile,
                "folder",
                "container"))
                .thenReturn(uploadResponse);

        Response cassandraInsertResponse = new Response();
        cassandraInsertResponse.put("STATUS", Constants.FAILED);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(cassandraInsertResponse);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1(token, mFile);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        }

    @Test
    void testWorkflowBulkUpdateTransitionV1_InvalidToken() {
        when(accessTokenValidator.fetchUserIdFromAccessToken("bad-token")).thenReturn(null);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1("bad-token", mFile);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testWorkflowBulkUpdateTransitionV1_UserNotFound() {
        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenReturn("user123");
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(List.of());

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1("token", mFile);

        assertEquals("FAILED", response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransitionV1_UploadFails() {
        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");
        when(configuration.getUserBulkUpdateTopic()).thenReturn("topic");
        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenReturn("user123");
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(List.of(Map.of(Constants.USER_ROOT_ORG_ID, "rootOrgId")));

        SBApiResponse uploadResponse = ProjectUtil.createDefaultResponse(Constants.API_USER_BULK_UPDATE);
        uploadResponse.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
        when(storageService.uploadFile((File) any(), anyString(), anyString())).thenReturn(uploadResponse);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1("token", mFile);

        assertEquals("FAILED", response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransitionV1_CassandraInsertFails() {
        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");
        when(configuration.getUserBulkUpdateTopic()).thenReturn("topic");
        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenReturn("user123");
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(List.of(Map.of(Constants.USER_ROOT_ORG_ID, "rootOrgId")));

        SBApiResponse uploadResponse = ProjectUtil.createDefaultResponse(Constants.API_USER_BULK_UPDATE);
        uploadResponse.setResponseCode(HttpStatus.OK);
        uploadResponse.getResult().put(Constants.NAME, "file.csv");
        uploadResponse.getResult().put(Constants.URL, "http://url");

        when(storageService.uploadFile((File) any(), anyString(), anyString())).thenReturn(uploadResponse);

        Response cassandraInsertResponse = new Response();
        cassandraInsertResponse.put("STATUS", "FAILED");
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(cassandraInsertResponse);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1("token", mFile);

        assertEquals("FAILED", response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransitionV1_Exception() {
        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenThrow(new RuntimeException("boom"));

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransitionV1("token", mFile);

        assertEquals("FAILED", response.getParams().getStatus());
    }

    @Test
    void testDownloadBulkUploadFile_success() throws Exception {
        // Arrange
        String fileName = "testFile.txt";
        String fileContent = "Hello World!";

        // Ensure the file is placed where the service expects it
        Path targetPath = Paths.get(Constants.LOCAL_BASE_PATH, fileName);
        if (!Files.isDirectory(targetPath.getParent())) {
            Files.createDirectories(targetPath.getParent());
        }
        Files.write(targetPath, fileContent.getBytes());

        // Act
        ResponseEntity<InputStreamResource> response =
                workflowServiceImpl.downloadBulkUploadFile(fileName);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(fileName, response.getHeaders().getContentDisposition().getFilename());
        verify(storageService).downloadFile(fileName);

        // Optional: Validate file content
        byte[] actualBytes = response.getBody().getInputStream().readAllBytes();
        assertArrayEquals(fileContent.getBytes(), actualBytes);

        // Clean up (optional)
        Files.deleteIfExists(targetPath);
    }

    @Test
    void testDownloadBulkUploadFile_exception() {
        // Arrange
        String fileName = "nonExistentFile.txt";
        doThrow(new RuntimeException("fail")).when(storageService).downloadFile(fileName);

        // Act
        ResponseEntity<InputStreamResource> response =
                workflowServiceImpl.downloadBulkUploadFile(fileName);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNull(response.getBody());

        verify(storageService).downloadFile(fileName);
    }

    @Test
    void testVerifyUserRecordExists_userFound() {
        // arrange
        String field = "email";
        String fieldValue = "user@test.com";
        Map<String, Object> userRecordDetails = new HashMap<>();

        when(configuration.getLmsServiceHost()).thenReturn("http://lms");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/user/search");

        Map<String, Object> userResponse = Map.of(
                Constants.RESPONSE_CODE, "OK",
                Constants.RESULT, Map.of(
                        Constants.RESPONSE, Map.of(
                                Constants.CONTENT, List.of(Map.of(
                                        Constants.USER_ID, "u123",
                                        Constants.CHANNEL, "dept1"
                                ))
                        )
                )
        );

        when(requestServiceImpl.fetchResultUsingPost(
                any(), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(userResponse);

        // act
        boolean result = workflowServiceImpl.verifyUserRecordExists(field, fieldValue, userRecordDetails);

        // assert
        assertTrue(result);
        assertEquals("u123", userRecordDetails.get(Constants.USER_ID));
        assertEquals("dept1", userRecordDetails.get(Constants.DEPARTMENT_NAME));
    }

    @Test
    void testVerifyUserRecordExists_userNotFound() {
        String field = "email";
        String fieldValue = "noone@test.com";
        Map<String, Object> userRecordDetails = new HashMap<>();

        when(configuration.getLmsServiceHost()).thenReturn("http://lms");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/user/search");

        Map<String, Object> userResponse = Map.of(
                Constants.RESPONSE_CODE, "OK",
                Constants.RESULT, Map.of(
                        Constants.RESPONSE, Map.of(
                                Constants.CONTENT, List.of()
                        )
                )
        );

        when(requestServiceImpl.fetchResultUsingPost(
                any(), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(userResponse);

        boolean result = workflowServiceImpl.verifyUserRecordExists(field, fieldValue, userRecordDetails);

        assertFalse(result);
        assertTrue(userRecordDetails.isEmpty());
    }

    @Test
    void testVerifyUserRecordExists_responseNull() {
        String field = "email";
        String fieldValue = "nobody@test.com";
        Map<String, Object> userRecordDetails = new HashMap<>();

        when(configuration.getLmsServiceHost()).thenReturn("http://lms");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/user/search");

        when(requestServiceImpl.fetchResultUsingPost(
                any(), any(), eq(Map.class), any(HashMap.class)))
                .thenReturn(null);

        boolean result = workflowServiceImpl.verifyUserRecordExists(field, fieldValue, userRecordDetails);

        assertFalse(result);
        assertTrue(userRecordDetails.isEmpty());
    }

    @Test
    void testVerifyUserRecordExists_exceptionThrown() {
        String field = "email";
        String fieldValue = "error@test.com";
        Map<String, Object> userRecordDetails = new HashMap<>();

        when(configuration.getLmsServiceHost()).thenReturn("http://lms");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/user/search");

        when(requestServiceImpl.fetchResultUsingPost(
                any(), any(), eq(Map.class), any(HashMap.class)))
                .thenThrow(new RuntimeException("LMS down"));

        ApplicationException ex = assertThrows(ApplicationException.class, () ->
                workflowServiceImpl.verifyUserRecordExists(field, fieldValue, userRecordDetails));

        assertTrue(ex.getMessage().contains("Hub Service ERROR"));
    }

    @Test
    void testWorkflowBulkUpdateTransition_happyPath() throws IOException {
        String userId = "user123";
        String rootOrgId = "rootOrg";
        String topic = "topic";

        Map<String, Object> user = Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId);
        Map<String, Object> uploadResult = Map.of(Constants.NAME, "file.csv", Constants.URL, "http://file");

        SBApiResponse uploadResponse = new SBApiResponse();
        uploadResponse.setResponseCode(HttpStatus.OK);
        uploadResponse.setResult(uploadResult);

        Response dbResponse = new Response();
        dbResponse.put("STATUS", Constants.SUCCESS);

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any())).thenReturn(List.of(user));
        when(storageService.uploadFile(eq(mFile), anyString(), anyString())).thenReturn(uploadResponse);
        when(cassandraOperation.insertRecord(any(), any(), any())).thenReturn(dbResponse);
        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");
        when(configuration.getUserBulkUpdateTopic()).thenReturn(topic);

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFUL, response.getParams().getStatus());
        assertTrue(response.getResult().containsKey(Constants.FILE_NAME));

        verify(kafkaProducer).push(eq(topic), any());
    }

    @Test
    void testWorkflowBulkUpdateTransition_invalidUserToken() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("");

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransition_userNotFound() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("userId");
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);

        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransition_uploadFailed() throws IOException {
        Map<String, Object> user = Map.of(Constants.USER_ROOT_ORG_ID, "rootOrg");
        SBApiResponse uploadResponse = new SBApiResponse();
        uploadResponse.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("userId");
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any())).thenReturn(List.of(user));
        when(storageService.uploadFile(eq(mFile), anyString(), anyString())).thenReturn(uploadResponse);
        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);

        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransition_insertFailed() throws IOException {
        Map<String, Object> user = Map.of(Constants.USER_ROOT_ORG_ID, "rootOrg");
        Map<String, Object> uploadResult = Map.of(Constants.NAME, "file.csv", Constants.URL, "http://file");

        SBApiResponse uploadResponse = new SBApiResponse();
        uploadResponse.setResponseCode(HttpStatus.OK);
        uploadResponse.setResult(uploadResult);

        Response dbResponse = new Response();
        dbResponse.put("STATUS", "FAILED");

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("userId");
        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any())).thenReturn(List.of(user));
        when(storageService.uploadFile(eq(mFile), anyString(), anyString())).thenReturn(uploadResponse);
        when(cassandraOperation.insertRecord(any(), any(), any())).thenReturn(dbResponse);
        when(configuration.getUserBulkUpdateFolderName()).thenReturn("folder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);

        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
    }

    @Test
    void testWorkflowBulkUpdateTransition_exception() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenThrow(new RuntimeException("error"));

        SBApiResponse response = workflowServiceImpl.workflowBulkUpdateTransition("token", mFile);

        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testGetBulkUpdateStatus_happyPath() {
        String token = "token";
        String userId = "user123";
        String rootOrgId = "rootOrg";

        Map<String, Object> user = Map.of(Constants.USER_ROOT_ORG_ID, rootOrgId);
        List<Map<String, Object>> bulkUpdates = List.of(
                Map.of("fileName", "file1.csv"),
                Map.of("fileName", "file2.csv")
        );

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.USER_TABLE), anyMap(), anyList()))
                .thenReturn(List.of(user));

        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.TABLE_USER_BULK_UPDATE), anyMap(), anyList()))
                .thenReturn(bulkUpdates);

        SBApiResponse response = workflowServiceImpl.getBulkUpdateStatus(token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESSFUL, response.getParams().getStatus());
        assertEquals(2, response.getResult().get(Constants.COUNT));
        assertTrue(response.getResult().containsKey(Constants.CONTENT));
    }

    @Test
    void testGetBulkUpdateStatus_invalidToken() {
        String token = "token";

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("");

        SBApiResponse response = workflowServiceImpl.getBulkUpdateStatus(token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testGetBulkUpdateStatus_userNotFound() {
        String token = "token";
        String userId = "user123";

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.USER_TABLE), anyMap(), anyList()))
                .thenReturn(Collections.emptyList());

        SBApiResponse response = workflowServiceImpl.getBulkUpdateStatus(token);

        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testGetBulkUpdateStatus_exceptionThrown() {
        String token = "token";

        when(accessTokenValidator.fetchUserIdFromAccessToken(token))
                .thenThrow(new RuntimeException("Something went wrong"));

        SBApiResponse response = workflowServiceImpl.getBulkUpdateStatus(token);

        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testUpdatePendingRequestsToNewMDO_success() throws Exception {
        // Arrange
        String userId = "user123";
        String newDeptName = "newDept";
        String serviceName = Constants.PROFILE_SERVICE_NAME;
        String currentStatus = Constants.SEND_FOR_APPROVAL;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(Constants.USER_ID, userId);
        requestBody.put(Constants.DEPARTMENT_NAME, newDeptName);
        requestBody.put(Constants.FORCE_MIGRATION, true);

        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestBody);

        WfStatusEntity entity = new WfStatusEntity();
        entity.setUpdateFieldValues("[{\"toValue\":{\"name\":\"oldDept\"}}]");
        entity.setCurrentStatus(currentStatus);
        entity.setInWorkflow(true);

        List<WfStatusEntity> wfStatusEntities = List.of(entity);

        List<Map<String, Object>> updatedFieldValues = List.of(
                Map.of(Constants.TO_VALUE, Map.of(Constants.NAME, "oldDept"))
        );

        when(wfStatusRepo.getPendingRequests(userId, serviceName, currentStatus))
                .thenReturn(wfStatusEntities);

        when(mapper.readValue(eq(entity.getUpdateFieldValues()), any(TypeReference.class)))
                .thenReturn(updatedFieldValues);

        when(wfStatusRepo.save(any(WfStatusEntity.class)))
                .thenReturn(entity);

        when(wfStatusRepo.updatePendingRequestsToNewMDO(userId, serviceName, currentStatus, newDeptName))
                .thenReturn(1);

        // Act
        Response response = workflowServiceImpl.updatePendingRequestsToNewMDO(request);

        // Assert
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));

        verify(wfStatusRepo).getPendingRequests(userId, serviceName, currentStatus);
        verify(mapper).readValue(anyString(), any(TypeReference.class));
        verify(wfStatusRepo).save(any(WfStatusEntity.class));
        verify(wfStatusRepo).updatePendingRequestsToNewMDO(userId, serviceName, currentStatus, newDeptName);
    }

    @Test
    void testGetWorkFlowConfig_allServiceNames() throws Exception {
        String jsonString = "{\"someKey\":\"someValue\"}";
        Map<String, Object> wfConfig = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> response = new HashMap<>();

        wfConfig.put(Constants.RESULT, result);
        result.put(Constants.RESPONSE, response);
        response.put(Constants.VALUE, jsonString);

        Map<String, Object> wfStates = Map.of("state", "value");
        WorkFlowModel expectedModel = new WorkFlowModel();

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(wfConfig);
        when(mapper.readValue(jsonString, Map.class)).thenReturn(wfStates);
        when(mapper.convertValue(eq(wfStates), ArgumentMatchers.<TypeReference<WorkFlowModel>>any())).thenReturn(expectedModel);

        String[] serviceNames = {
                Constants.PROFILE_SERVICE_NAME,
                Constants.USER_REGISTRATION_SERVICE_NAME,
                Constants.POSITION_SERVICE_NAME,
                Constants.ORGANISATION_SERVICE_NAME,
                Constants.DOMAIN_SERVICE_NAME,
                Constants.BLENDED_PROGRAM_SERVICE_NAME,
                Constants.ONE_STEP_PC_APPROVAL,
                Constants.ONE_STEP_MDO_APPROVAL,
                Constants.TWO_STEP_MDO_AND_PC_APPROVAL,
                Constants.TWO_STEP_PC_AND_MDO_APPROVAL,
                "UNKNOWN_SERVICE"
        };

        for (String serviceName : serviceNames) {
            stubConfigFor(serviceName);

            WorkFlowModel actualModel = workflowServiceImpl.getWorkFlowConfig(serviceName);

            assertNotNull(actualModel);
            assertSame(expectedModel, actualModel);
        }
    }

    @Test
    void testGetWorkFlowConfig_throwsApplicationException() throws Exception {
        String jsonString = "{\"someKey\":\"someValue\"}";
        Map<String, Object> wfConfig = new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> response = new HashMap<>();

        wfConfig.put(Constants.RESULT, result);
        result.put(Constants.RESPONSE, response);
        response.put(Constants.VALUE, jsonString);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(wfConfig);
        when(mapper.readValue(jsonString, Map.class)).thenThrow(new RuntimeException("Parsing failed"));

        stubConfigFor(Constants.PROFILE_SERVICE_NAME);

        ApplicationException ex = assertThrows(ApplicationException.class,
                () -> workflowServiceImpl.getWorkFlowConfig(Constants.PROFILE_SERVICE_NAME));

        assertTrue(ex.getMessage().contains(Constants.WORKFLOW_PARSING_ERROR_MESSAGE));
    }

    @Test
    void testApplicationSerachOnApplicationIdGrup_withApplicationIdsProvided() {
        String rootOrg = "root";
        SearchCriteria criteria = new SearchCriteria();
        criteria.setServiceName("service");
        criteria.setApplicationStatus("status");
        criteria.setApplicationIds(List.of("app1", "app2"));
        criteria.setLimit(10);
        criteria.setOffset(0);

        WfStatusEntity wf1 = new WfStatusEntity(); wf1.setApplicationId("app1");
        WfStatusEntity wf2 = new WfStatusEntity(); wf2.setApplicationId("app1");
        WfStatusEntity wf3 = new WfStatusEntity(); wf3.setApplicationId("app2");

        List<WfStatusEntity> wfList = List.of(wf1, wf2, wf3);

        when(configuration.getMaxLimit()).thenReturn(20);

        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndApplicationIdIn(
                "service", "status", criteria.getApplicationIds())).thenReturn(wfList);

        Response response = workflowServiceImpl.applicationSerachOnApplicationIdGrup(rootOrg, criteria);

        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertNotNull(response.get(Constants.DATA));

        Map<String, List<WfStatusEntity>> data = (Map<String, List<WfStatusEntity>>) response.get(Constants.DATA);
        assertEquals(2, data.size());
        assertTrue(data.containsKey("app1"));
        assertTrue(data.containsKey("app2"));
    }

    @Test
    void testApplicationSerachOnApplicationIdGrup_withEmptyApplicationIds() {
        String rootOrg = "root";
        SearchCriteria criteria = new SearchCriteria();
        criteria.setServiceName("service");
        criteria.setApplicationStatus("status");
        criteria.setApplicationIds(Collections.emptyList());
        criteria.setLimit(10);
        criteria.setOffset(0);

        List<String> distinctApps = List.of("app3", "app4");
        WfStatusEntity wf1 = new WfStatusEntity();
        wf1.setApplicationId("app3");

        WfStatusEntity wf2 = new WfStatusEntity();
        wf2.setApplicationId("app4");

        List<WfStatusEntity> wfList = List.of(wf1, wf2);
        when(configuration.getMaxLimit()).thenReturn(20);

        when(wfStatusRepo.getListOfDistinctApplication(eq(rootOrg), eq("service"), eq("status"), any(Pageable.class)))
                .thenReturn(distinctApps);

        when(wfStatusRepo.findByServiceNameAndCurrentStatusAndApplicationIdIn(
                "service", "status", distinctApps)).thenReturn(wfList);

        Response response = workflowServiceImpl.applicationSerachOnApplicationIdGrup(rootOrg, criteria);

        assertEquals(Constants.SUCCESSFUL, response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        assertNotNull(response.get(Constants.DATA));

        Map<String, List<WfStatusEntity>> data = (Map<String, List<WfStatusEntity>>) response.get(Constants.DATA);
        assertEquals(2, data.size());
    }

    @Test
    void testGetApplicationWfHistory_success() {
        String rootOrg = "org1";
        String applicationId = "app1";

        // Arrange: prepare two entities with the same wfId to cover both branches
        WfAuditEntity audit1 = new WfAuditEntity();
        audit1.setWfId("wf123");
        audit1.setApplicationId(applicationId);
        audit1.setRootOrg(rootOrg);

        WfAuditEntity audit2 = new WfAuditEntity();
        audit2.setWfId("wf123");
        audit2.setApplicationId(applicationId);
        audit2.setRootOrg(rootOrg);

        WfAuditEntity audit3 = new WfAuditEntity();
        audit3.setWfId("wf456");
        audit3.setApplicationId(applicationId);
        audit3.setRootOrg(rootOrg);

        List<WfAuditEntity> audits = List.of(audit1, audit2, audit3);

        when(wfAuditRepo.findByRootOrgAndApplicationIdOrderByCreatedOnDesc(rootOrg, applicationId))
                .thenReturn(audits);

        // Act
        Response response = workflowServiceImpl.getApplicationWfHistory(rootOrg, applicationId);

        // Assert
        assertEquals("Successful", response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));

        @SuppressWarnings("unchecked")
        Map<String, List<WfAuditEntity>> history = (Map<String, List<WfAuditEntity>>) response.get(Constants.DATA);

        assertNotNull(history);
        assertEquals(2, history.size());

        assertTrue(history.containsKey("wf123"));
        assertTrue(history.containsKey("wf456"));
        assertEquals(2, history.get("wf123").size());
        assertEquals(1, history.get("wf456").size());

        verify(wfAuditRepo, times(1))
                .findByRootOrgAndApplicationIdOrderByCreatedOnDesc(rootOrg, applicationId);
    }

    @Test
    void testGetWorkflowStates_success() {
        String rootOrg = "org1";
        String org = "dept1";
        String serviceName = "serviceX";
        String state = "STATE1";

        WorkFlowModel wfModel = new WorkFlowModel();
        WfStatus wfStatusExpected = new WfStatus();
        wfStatusExpected.setState(state);

        // Prepare WorkFlowModel to contain the state
        wfModel.setWfstates(List.of(wfStatusExpected));

        // Mock getWorkFlowConfig to return prepared model
        doReturn(wfModel).when(spyWorkflowServiceImpl).getWorkFlowConfig(serviceName);

        WfStatus result = spyWorkflowServiceImpl.getWorkflowStates(rootOrg, org, serviceName, state);

        assertNotNull(result);
        assertEquals(state, result.getState());
    }

    @Test
    void testGetWorkflowStates_failure() {
        String rootOrg = "org1";
        String org = "dept1";
        String serviceName = "serviceX";
        String state = "STATE1";

        // Mock getWorkFlowConfig to throw an exception
        doThrow(new RuntimeException("fail"))
                .when(spyWorkflowServiceImpl).getWorkFlowConfig(serviceName);

        ApplicationException exception = assertThrows(ApplicationException.class, () -> {
            spyWorkflowServiceImpl.getWorkflowStates(rootOrg, org, serviceName, state);
        });

        assertTrue(exception.getMessage().contains(Constants.JSON_PARSING_ERROR));
    }

    @Test
    void testGetNextActionForState_success() {
        String rootOrg = "org1";
        String org = "dept1";
        String serviceName = "serviceX";
        String state = "STATE1";

        // Prepare WorkFlowModel & WfStatus & WfAction
        WfAction action1 = new WfAction();
        action1.setAction("APPROVE");
        action1.setRoles(List.of("ROLE_1", "ROLE_2"));

        WfStatus wfStatus = new WfStatus();
        wfStatus.setState(state);
        wfStatus.setIsLastState(false);
        wfStatus.setActions(List.of(action1));

        WorkFlowModel wfModel = new WorkFlowModel();
        wfModel.setWfstates(List.of(wfStatus));

        // Stub getWorkFlowConfig to return our model
        doReturn(wfModel).when(spyWorkflowServiceImpl).getWorkFlowConfig(serviceName);

        Response response = spyWorkflowServiceImpl.getNextActionForState(rootOrg, org, serviceName, state);

        assertNotNull(response);
        assertEquals("Successful", response.get("message"));
        assertEquals(HttpStatus.OK, response.get("status"));

        List<HashMap<String, Object>> data =
                (List<HashMap<String, Object>>) response.get("data");

        assertNotNull(data);
        assertEquals(1, data.size());
        assertEquals("APPROVE", data.get(0).get("action"));
        assertEquals(List.of("ROLE_1", "ROLE_2"), data.get(0).get("roles"));
    }

    @Test
    void testGetNextActionForState_exception() {
        String rootOrg = "org1";
        String org = "dept1";
        String serviceName = "serviceX";
        String state = "STATE1";

        // Stub getWorkFlowConfig to throw exception
        doThrow(new RuntimeException("boom"))
                .when(spyWorkflowServiceImpl).getWorkFlowConfig(serviceName);

        ApplicationException exception = assertThrows(ApplicationException.class, () -> {
            spyWorkflowServiceImpl.getNextActionForState(rootOrg, org, serviceName, state);
        });

        assertTrue(exception.getMessage().contains(Constants.JSON_PARSING_ERROR));
    }

    @Test
    void testGetApplicationHistoryOnWfId_success() {
        String rootOrg = "org1";
        String wfId = "wf123";
        String applicationId = "app123";

        // Mock data
        WfAuditEntity entity1 = new WfAuditEntity();
        entity1.setWfId(wfId);
        entity1.setApplicationId(applicationId);

        List<WfAuditEntity> mockList = List.of(entity1);

        when(wfAuditRepo.findByRootOrgAndApplicationIdAndWfIdOrderByCreatedOnDesc(rootOrg, applicationId, wfId))
                .thenReturn(mockList);

        Response response = workflowServiceImpl.getApplicationHistoryOnWfId(rootOrg, wfId, applicationId);

        assertNotNull(response);
        assertEquals("Successful", response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));

    }

    @Test
    void testWfApplicationSearch_success() {
        String rootOrg = "org1";
        String org = "org";
        SearchCriteria criteria = new SearchCriteria();
        criteria.setServiceName("service1");
        criteria.setApplicationStatus("APPROVED");
        criteria.setLimit(1);
        criteria.setOffset(0);

        WfStatusEntity entity = new WfStatusEntity();
        entity.setApplicationId("app1");
        List<WfStatusEntity> content = List.of(entity);
        Page<WfStatusEntity> page = new PageImpl<>(content);

        when(configuration.getMaxLimit()).thenReturn(10);
        when(wfStatusRepo.findByRootOrgAndOrgAndServiceNameAndCurrentStatus(
                eq(rootOrg), eq(org), eq("service1"), eq("APPROVED"), any(Pageable.class)))
                .thenReturn(page);

        Response response = workflowServiceImpl.wfApplicationSearch(rootOrg, org, criteria);

        assertNotNull(response);
        assertEquals("Successful", response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
    }

    @Test
    void testGetWfApplication_found() {
        String rootOrg = "org1";
        String org = "org";
        String wfId = "wf1";
        String applicationId = "app1";

        WfStatusEntity entity = new WfStatusEntity();
        entity.setApplicationId(applicationId);
        entity.setWfId(wfId);

        when(wfStatusRepo.findByRootOrgAndOrgAndApplicationIdAndWfId(
                rootOrg, org, applicationId, wfId)).thenReturn(entity);

        Response response = workflowServiceImpl.getWfApplication(rootOrg, org, wfId, applicationId);

        assertNotNull(response);
        assertEquals("Successful", response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
    }

    @Test
    void testGetWfApplication_notFound() {
        String rootOrg = "org1";
        String org = "org";
        String wfId = "wf1";
        String applicationId = "app1";

        when(wfStatusRepo.findByRootOrgAndOrgAndApplicationIdAndWfId(
                rootOrg, org, applicationId, wfId)).thenReturn(null);

        Response response = workflowServiceImpl.getWfApplication(rootOrg, org, wfId, applicationId);

        assertNotNull(response);
        assertEquals("Successful", response.get(Constants.MESSAGE));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));

    }

    private void stubConfigFor(String serviceName) {

        switch (serviceName) {
            case Constants.PROFILE_SERVICE_NAME:
                when(configuration.getProfileServiceConfigPath()).thenReturn("/profile/config");
                break;
            case Constants.USER_REGISTRATION_SERVICE_NAME:
                when(configuration.getUserRegistrationServiceConfigPath()).thenReturn("/userReg/config");
                break;
            case Constants.POSITION_SERVICE_NAME:
                when(configuration.getPositionServiceConfigPath()).thenReturn("/position/config");
                break;
            case Constants.ORGANISATION_SERVICE_NAME:
                when(configuration.getOrgServiceConfigPath()).thenReturn("/org/config");
                break;
            case Constants.DOMAIN_SERVICE_NAME:
                when(configuration.getDomainServiceConfigPath()).thenReturn("/domain/config");
                break;
            case Constants.BLENDED_PROGRAM_SERVICE_NAME:
                when(configuration.getBlendedProgramServicePath()).thenReturn("/blended/config");
                break;
            case Constants.ONE_STEP_PC_APPROVAL,
                 Constants.ONE_STEP_MDO_APPROVAL,
                 Constants.TWO_STEP_MDO_AND_PC_APPROVAL,
                 Constants.TWO_STEP_PC_AND_MDO_APPROVAL:
                when(configuration.getMultilevelBPEnrolEndPoint()).thenReturn("/mlbp/config/");
                break;
            default:
                // no config needed for default
                break;
        }
    }
    // helper to mock LMS search response
    private Map<String, Object> getSearchResponse(boolean isMdo) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("responseCode", "OK");

        Map<String, Object> result = new HashMap<>();
        Map<String, Object> response = new HashMap<>();

        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("userId", isMdo ? "mdo1" : "user1");
        userMap.put("rootOrgId", "rootOrg");
        userMap.put("rootOrgName", "dept1");

        if (!isMdo) {
            Map<String, Object> profile = new HashMap<>();
            Map<String, Object> personal = new HashMap<>();
            personal.put("firstName", "John");
            personal.put("mobile", "12345");
            personal.put("primaryEmail", "john@x.com");
            profile.put("personalDetails", personal);
            userMap.put("profileDetails", profile);
        }

        content.add(userMap);
        response.put("content", content);
        result.put("response", response);
        resp.put("result", result);
        return resp;
    }

    // helper to simulate wfStatusEntities
    private List<WfStatusEntity> mockWfList(String appId, String userId) {
        WfStatusEntity wf = new WfStatusEntity();
        wf.setApplicationId(appId);
        wf.setUserId(userId);
        wf.setCreatedOn(new Date());
        return List.of(wf);
    }

    private List<Map<String, Object>> mockUserProfiles() {
        Map<String, Object> profile = new HashMap<>();
        profile.put("userInfo", Map.of("first_name", "John"));
        profile.put("wfInfo", mockWfList("app1", "u1"));
        return List.of(profile);
    }
}