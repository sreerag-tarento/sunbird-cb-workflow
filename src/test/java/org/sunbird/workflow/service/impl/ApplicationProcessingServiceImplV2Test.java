package org.sunbird.workflow.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.service.*;

import java.util.*;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationProcessingServiceImplV2Test {

    @InjectMocks
    private ApplicationProcessingServiceImplV2 service;

    @Mock
    private UserProfileWfService userProfileWfService;

    @Mock
    private Configuration configuration;

    @Mock
    private RequestServiceImpl requestService;

    @Mock
    private WfStatusRepo wfStatusRepo;

    @Mock
    private UserRegistrationWfService userRegService;

    @Mock
    private BPWorkFlowService bpWorkFlowService;

    @Mock
    private DomainWhiteListWorkFlowService domainWhiteListWorkFlowService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testProcessWfApplicationRequest_Profile() {
        List<WfRequest> requests = List.of(new WfRequest());
        service.processWfApplicationRequest(requests, Constants.PROFILE_SERVICE_NAME, "user1", "token1");
        verify(userProfileWfService).updateUserProfileV2(requests, "user1", "token1");
    }

    @Test
    void testProcessWfApplicationRequest_UserProfileFlag() {
        List<WfRequest> requests = List.of(new WfRequest());
        service.processWfApplicationRequest(requests, Constants.USER_PROFILE_FLAG_SERVICE, "user1", "token1");
        verify(userProfileWfService).updateUserProfileV2(requests, "user1", "token1");
    }

    @Test
    void testProcessWfApplicationRequest_UserRegistration() {
        WfRequest req = mock(WfRequest.class);
        List<WfRequest> requests = List.of(req);
        service.processWfApplicationRequest(requests, Constants.USER_REGISTRATION_SERVICE_NAME, "user1", null);
        verify(userRegService).processMessage(req);
    }

    @Test
    void testProcessWfApplicationRequest_Domain() {
        WfRequest req = mock(WfRequest.class);
        List<WfRequest> requests = List.of(req);
        service.processWfApplicationRequest(requests, Constants.DOMAIN, "user1", null);
        verify(domainWhiteListWorkFlowService).processDomainRequest(req);
        verify(bpWorkFlowService).processWFRequest(req);
    }

    @Test
    void testProcessWfApplicationRequest_BlendedProgram() {
        WfRequest req = mock(WfRequest.class);
        List<WfRequest> requests = List.of(req);
        service.processWfApplicationRequest(requests, Constants.BLENDED_PROGRAM_SERVICE_NAME, "user1", null);
        verify(bpWorkFlowService).processWFRequest(req);
    }

    @Test
    void testProcessWfApplicationRequest_OneStepMdo() {
        WfRequest req = mock(WfRequest.class);
        List<WfRequest> requests = List.of(req);
        service.processWfApplicationRequest(requests, Constants.ONE_STEP_MDO_APPROVAL, "user1", null);
        verify(bpWorkFlowService).processWFRequest(req);
    }

    @Test
    void testProcessWfApplicationRequest_TwoStep() {
        WfRequest req = mock(WfRequest.class);
        List<WfRequest> requests = List.of(req);
        service.processWfApplicationRequest(requests, Constants.TWO_STEP_MDO_AND_PC_APPROVAL, "user1", null);
        verify(bpWorkFlowService).processWFRequest(req);
    }

    @Test
    void testProcessWfApplicationRequest_Default() {
        List<WfRequest> requests = List.of(new WfRequest());
        service.processWfApplicationRequest(requests, "UNKNOWN", "user1", null);
        verifyNoInteractions(userProfileWfService, userRegService, bpWorkFlowService, domainWhiteListWorkFlowService);
    }

    @Test
    void testUpdateDepartmentToPortalDBs_Approved_Profile() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("app1");
        wfRequest.setWfId("wf1");
        wfRequest.setServiceName(Constants.PROFILE_SERVICE_NAME);

        HashMap<String, Object> fromValue = new HashMap<>();
        fromValue.put("name", "oldDept");

        HashMap<String, Object> toValue = new HashMap<>();
        toValue.put("name", "newDept");

        HashMap<String, Object> updatedMap = new HashMap<>();
        updatedMap.put("fieldKey", "professionalDetails");
        updatedMap.put("fromValue", fromValue);
        updatedMap.put("toValue", toValue);

        wfRequest.setUpdateFieldValues(List.of(updatedMap));

        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus(Constants.APPROVED_STATE);
        entity.setRootOrg("rootOrg");
        entity.setOrg("org");

        when(wfStatusRepo.findByApplicationIdAndWfId("app1", "wf1")).thenReturn(entity);

        service.updateDepartmentToPortalDBs(List.of(wfRequest));

        verify(requestService).fetchResultUsingPost(any(), any(), eq(Map.class), any());
    }

    @Test
    void testUpdateDepartmentToPortalDBs_NotApproved() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("app1");
        wfRequest.setWfId("wf1");
        wfRequest.setServiceName(Constants.PROFILE_SERVICE_NAME);

        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus("REJECTED");

        when(wfStatusRepo.findByApplicationIdAndWfId("app1", "wf1")).thenReturn(entity);

        service.updateDepartmentToPortalDBs(List.of(wfRequest));

        verifyNoInteractions(requestService);
    }

    @Test
    void testUpdateDepartmentToPortalDBs_EmptyFieldKey() {
        WfRequest wfRequest = new WfRequest();
        wfRequest.setApplicationId("app1");
        wfRequest.setWfId("wf1");
        wfRequest.setServiceName(Constants.PROFILE_SERVICE_NAME);

        HashMap<String, Object> updatedMap = new HashMap<>();
        updatedMap.put("fieldKey", null);

        wfRequest.setUpdateFieldValues(List.of(updatedMap));

        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus(Constants.APPROVED_STATE);

        when(wfStatusRepo.findByApplicationIdAndWfId("app1", "wf1")).thenReturn(entity);

        service.updateDepartmentToPortalDBs(List.of(wfRequest));

        verifyNoInteractions(requestService);
    }

    @Test
    void testUpdateUserDepartment_Success() {
        when(configuration.getLexCoreServiceHost()).thenReturn("http://host");
        when(configuration.getDepartmentUpdatePath()).thenReturn("/update");

        HashMap<String, Object> deptInfo = new HashMap<>();
        deptInfo.put("userId", "u1");

        service.updateUserDepartment("rootOrg", "org", "wid", deptInfo);

        verify(requestService).fetchResultUsingPost(any(), eq(deptInfo), eq(Map.class), any());
    }

    @Test
    void testUpdateUserDepartment_Exception() {
        when(configuration.getLexCoreServiceHost()).thenReturn("http://host");
        when(configuration.getDepartmentUpdatePath()).thenReturn("/update");

        doThrow(new RuntimeException("fail")).when(requestService)
                .fetchResultUsingPost(any(), any(), eq(Map.class), any());

        HashMap<String, Object> deptInfo = new HashMap<>();
        deptInfo.put("userId", "u1");

        assertDoesNotThrow(() ->
                service.updateUserDepartment("rootOrg", "org", "wid", deptInfo)
        );

        verify(requestService).fetchResultUsingPost(any(), any(), eq(Map.class), any());
    }
}
