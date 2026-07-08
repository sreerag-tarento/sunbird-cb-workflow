package org.sunbird.workflow.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.RedisCacheMgr;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.service.Workflowservice;

@ExtendWith(MockitoExtension.class)
class UserProfileWfServiceImpl2Test {

    @InjectMocks
    private UserProfileWfServiceImpl service;

    @Mock
    private Workflowservice workflowservice;

    @Mock
    private RequestServiceImpl requestServiceImpl;

    @Mock
    private Configuration configuration;

    @Mock
    private WfStatusRepo wfStatusRepo;

    @Mock
    private WorkflowServiceImpl workflowService;

    @Mock
    private WorkflowAuditProcessingServiceImpl workflowAuditProcessingService;

    @Mock
    private RedisCacheMgr redisCacheMgr;

    @Mock
    private Logger logger;

    @Mock
    private ObjectMapper mapper;

    private static final String USER_ID = "user-123";
    private static final String APP_ID = "app-123";
    private static final String WF_ID = "wf-123";

    private WfRequest createWfRequest(String serviceName) {
        WfRequest request = new WfRequest();
        request.setApplicationId(APP_ID);
        request.setWfId(WF_ID);
        request.setServiceName(serviceName);
        request.setUserId(USER_ID);

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("verifiedKarmayogi", true);
        toValue.put(Constants.NAME, "Ajay");
        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put("fieldKey", "verifiedKarmayogi");
        updateField.put("toValue", toValue);
        updateField.put("osid", "osid-1");

        request.setUpdateFieldValues(Collections.singletonList(updateField));
        return request;
    }

    @BeforeEach
    void init() {
        ReflectionTestUtils.setField(service, "logger", logger);
    }

    @Test
    void testUpdateUserProfileV2_userNotFound() {
        WfRequest request = createWfRequest("ProfileService");

        Map<String, Object> params = new HashMap<>();
        params.put("errmsg", "Invalid user");
        Map<String, Object> readData = new HashMap<>();
        readData.put("responseCode", "CLIENT_ERROR");
        readData.put("params", params);
        when(configuration.getLmsServiceHost()).thenReturn("http://lms-host/");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/read/" + Constants.USER_ID_VALUE);


        assertDoesNotThrow(()-> service.updateUserProfileV2(Collections.singletonList(request), USER_ID, null));

    }

    @Test
    void testUpdateUserProfileV2_verifiedKarmayogi_path() {
        WfRequest request = new WfRequest();
        request.setApplicationId(APP_ID);
        request.setWfId(WF_ID);
        request.setServiceName("ProfileService");
        request.setUserId(USER_ID);

        Map<String, Object> toValue = new HashMap<>();
        toValue.put("verifiedKarmayogi", true);
        toValue.put(Constants.NAME, "Ajay");
        HashMap<String, Object> updateField = new HashMap<>();
        updateField.put("fieldKey", "Karmayogi");
        updateField.put("toValue", toValue);
        updateField.put("osid", "osid-1");

        request.setUpdateFieldValues(Collections.singletonList(updateField));        request.setServiceName(Constants.PROFILE_SERVICE_NAME);
        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus("PROCESSED");

        Map<String, Object> profile = new HashMap<>();
        Map<String, Object> response = new HashMap<>();
        response.put("profileDetails", profile);
        Map<String, Object> result = new HashMap<>();
        result.put("response", response);
        Map<String, Object> readData = new HashMap<>();
        readData.put("responseCode", "OK");
        readData.put("result", result);

        when(configuration.getLmsServiceHost()).thenReturn("http://lms-host/");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/read/" + Constants.USER_ID_VALUE);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(readData);
        when(mapper.convertValue(any(), eq(Map.class))).thenReturn(readData);
        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCurrentStatus(Constants.APPROVED_STATE);
        when(wfStatusRepo.findByApplicationIdAndWfId(APP_ID, WF_ID)).thenReturn(wfStatusEntity);

        assertDoesNotThrow(()-> service.updateUserProfileV2(Collections.singletonList(request), USER_ID, null));

    }


    @Test
    void testUpdateUserProfileV2_verifiedKarmayogi_path_2() {
        WfRequest request = createWfRequest("UserProfileFlagService");
        request.setServiceName(Constants.USER_PROFILE_FLAG_SERVICE);
        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus("PROCESSED");

        Map<String, Object> profile = new HashMap<>();
        Map<String, Object> response = new HashMap<>();
        response.put("profileDetails", profile);
        Map<String, Object> result = new HashMap<>();
        result.put("response", response);
        Map<String, Object> readData = new HashMap<>();
        readData.put("responseCode", "OK");
        readData.put("result", result);

        when(configuration.getLmsServiceHost()).thenReturn("http://lms-host/");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/read/" + Constants.USER_ID_VALUE);

        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(readData);
        when(mapper.convertValue(any(), eq(Map.class))).thenReturn(readData);
        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setCurrentStatus(Constants.PROCESSED_STATE);
        when(wfStatusRepo.findByApplicationIdAndWfId(APP_ID, WF_ID)).thenReturn(wfStatusEntity);

        assertDoesNotThrow(()-> service.updateUserProfileV2(Collections.singletonList(request), USER_ID, null));

    }

    @Test
    void testUpdateUserProfileV2_updateRequestReturnsNull() {
        WfRequest request = createWfRequest("ProfileService");
        request.getUpdateFieldValues().get(0).put("fieldKey", "unknownKey");

        WfStatusEntity entity = new WfStatusEntity();
        entity.setCurrentStatus("APPROVED");

        Map<String, Object> profile = new HashMap<>();
        Map<String, Object> response = new HashMap<>();
        response.put("profileDetails", profile);
        Map<String, Object> result = new HashMap<>();
        result.put("response", response);
        Map<String, Object> readData = new HashMap<>();
        readData.put("responseCode", "Bad Request");
        readData.put("result", result);


        when(configuration.getLmsServiceHost()).thenReturn("http://lms-host/");
        when(configuration.getUserProfileReadEndPoint()).thenReturn("/user/read/" + Constants.USER_ID_VALUE);

        when(wfStatusRepo.findByApplicationIdAndWfId(APP_ID, WF_ID)).thenReturn(entity);
        when(requestServiceImpl.fetchResultUsingGet(any())).thenReturn(readData);
        when(mapper.convertValue(any(), eq(Map.class))).thenReturn(readData);

        assertDoesNotThrow(()-> service.updateUserProfileV2(Collections.singletonList(request), USER_ID, null));

    }
}