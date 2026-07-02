package org.sunbird.workflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.RedisCacheMgr;
import org.sunbird.workflow.models.SBApiResponse;
import org.sunbird.workflow.models.SunbirdApiRespParam;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.service.UserBulkUploadService;
import org.sunbird.workflow.service.UserProfileWfService;
import org.sunbird.workflow.utils.CassandraOperation;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBulkUploadService2Test {

    @InjectMocks
    private UserBulkUploadService service;

    @Mock
    private Configuration configuration;

    @Mock
    private UserProfileWfService userProfileWfService;

    @Mock
    private WfStatusRepo wfStatusRepo;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private RequestServiceImpl requestService;

    @Mock
    private StorageServiceImpl storageService;

    @Mock
    private CassandraOperation cassandraOperation;

    @Mock
    private RedisCacheMgr redisCacheMgr;

    private File tempFile;

    @BeforeEach
    void setUp() throws Exception {

        tempFile = File.createTempFile("bulk_upload", ".csv");
        FileWriter writer = new FileWriter(tempFile);
        writer.write("Full Name,Email,Phone,Group,Designation,Gender,Category,DOB,Mother Tongue,Employee ID,Office Pin Code,External System ID,External System,Tags\n");
        writer.write("John Doe,john@example.com,1234567890,Group1,Manager,Male,OBC,01-01-1990,English,EMP001,560001,SYS123,ExtSystem,Bihar Circle\n");
        writer.close();
    }

    @Test
    void testProcessBulkUploadV1_SuccessfulCase() throws Exception {
        Map<String, String> input = new HashMap<>();
        input.put(Constants.FILE_NAME, tempFile.getName());
        input.put(Constants.ROOT_ORG_ID, "root-org-1");
        input.put(Constants.IDENTIFIER, "bulk123");


        when(configuration.getCsvDelimiter()).thenReturn(',');
        when(configuration.getTagsDelimiter()).thenReturn(";");
        when(configuration.getLmsServiceHost()).thenReturn("http://lms.example.com/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/user/search");
        when(configuration.getGroupValues()).thenReturn(List.of("Group1"));
        when(configuration.getBulkUploadCategoryValue()).thenReturn(List.of("OBC"));
        when(configuration.getBulkUploadGenderValue()).thenReturn(List.of("Male"));

        // Sample headers and request object
        Map<String, String> headersValue = new HashMap<>();
        headersValue.put("Authorization", "Bearer token");

        Map<String, Object> requestObject = new HashMap<>();
        requestObject.put("query", "test");

// Prepare a fake response structure that matches expected hierarchy
        Map<String, Object> contentItem = new HashMap<>();
        contentItem.put(Constants.USER_ID, "user-123");
        contentItem.put(Constants.CHANNEL, "mdo-dept");
        contentItem.put(Constants.ROOT_ORG_ID, "root-org-1");

        List<Map<String, Object>> contents = List.of(contentItem);

        Map<String, Object> response = new HashMap<>();
        response.put(Constants.CONTENT, contents);

        Map<String, Object> result = new HashMap<>();
        result.put(Constants.RESPONSE, response);

        Map<String, Object> userSearchResult = new HashMap<>();
        userSearchResult.put(Constants.RESPONSE_CODE, "OK");
        userSearchResult.put(Constants.RESULT, result);

// Mocking requestServiceImpl.fetchResultUsingPost(...)
        when(requestService.fetchResultUsingPost(any(), any(), any(), any())).thenReturn(userSearchResult);

        Map<String, Object> mockUpdateResponse = new HashMap<>();
        mockUpdateResponse.put("status", "success");

        when(cassandraOperation.updateRecord(eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.TABLE_USER_BULK_UPDATE), anyMap(), anyMap())).thenReturn(mockUpdateResponse);

        // Stub RedisCacheMgr methods
        when(redisCacheMgr.keyExists(any())).thenReturn(true);
        when(redisCacheMgr.valueExists(any(), any())).thenReturn(true);

        String userId = "user-123";

        // Sample input
        Map<String, Object> valuesToBeUpdate = new HashMap<>();
        valuesToBeUpdate.put("firstname", "Ajay");

        Map<String, String> toValueMap = new HashMap<>();
        toValueMap.put("firstname", "Ajay");

        Map<String, Object> toValueWrapper = new HashMap<>();
        toValueWrapper.put(Constants.TO_VALUE, toValueMap);

        List<Map<String, Object>> updatedValues = List.of(toValueWrapper);

        String updateValuesJson = new ObjectMapper().writeValueAsString(updatedValues);

        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setUpdateFieldValues(updateValuesJson);
        wfStatusEntity.setCurrentStatus("true"); // dummy init

        List<WfStatusEntity> wfStatusEntityList = List.of(wfStatusEntity);

        // Mock WfStatusRepo
        when(wfStatusRepo.findByUserIdAndCurrentStatus(userId, true)).thenReturn(wfStatusEntityList);
        when(mapper.readValue(updateValuesJson, List.class)).thenReturn(updatedValues);
        SBApiResponse mockResponse = mock(SBApiResponse.class);

        when(storageService.uploadFile(any(File.class), anyString(), anyString())).thenReturn(mockResponse);

        // Perform
        assertDoesNotThrow(() -> service.processBulkUploadV1((HashMap<String, String>) input));

    }

    @Test
    void testProcessBulkUploadV1_Error() throws Exception {
        Map<String, String> input = new HashMap<>();
        input.put(Constants.FILE_NAME, tempFile.getName());
        input.put(Constants.ROOT_ORG_ID, "root-org-1");
        input.put(Constants.IDENTIFIER, "bulk123");


        when(configuration.getCsvDelimiter()).thenReturn(',');
        when(configuration.getTagsDelimiter()).thenReturn(";");
        when(configuration.getLmsServiceHost()).thenReturn("http://lms.example.com/");

        // Sample headers and request object
        Map<String, String> headersValue = new HashMap<>();
        headersValue.put("Authorization", "Bearer token");

        Map<String, Object> requestObject = new HashMap<>();
        requestObject.put("query", "test");

// Prepare a fake response structure that matches expected hierarchy
        Map<String, Object> contentItem = new HashMap<>();
        contentItem.put(Constants.USER_ID, "user-123");
        contentItem.put(Constants.CHANNEL, "mdo-dept");
        contentItem.put(Constants.ROOT_ORG_ID, "root-org-1");

        List<Map<String, Object>> contents = List.of(contentItem);

        Map<String, Object> response = new HashMap<>();
        response.put(Constants.CONTENT, contents);

        Map<String, Object> result = new HashMap<>();
        result.put(Constants.RESPONSE, response);

        Map<String, Object> userSearchResult = new HashMap<>();
        userSearchResult.put(Constants.RESPONSE_CODE, "OK");
        userSearchResult.put(Constants.RESULT, result);

// Mocking requestServiceImpl.fetchResultUsingPost(...)
        when(requestService.fetchResultUsingPost(any(), any(), any(), any())).thenReturn(userSearchResult);

        Map<String, Object> mockUpdateResponse = new HashMap<>();
        mockUpdateResponse.put("status", "success");

        when(cassandraOperation.updateRecord(eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.TABLE_USER_BULK_UPDATE), anyMap(), anyMap())).thenReturn(mockUpdateResponse);

        // Stub RedisCacheMgr methods
        when(redisCacheMgr.keyExists(any())).thenReturn(true);
        when(redisCacheMgr.valueExists(any(), any())).thenReturn(false);

        String userId = "user-123";

        // Sample input
        Map<String, Object> valuesToBeUpdate = new HashMap<>();
        valuesToBeUpdate.put("firstName", "Ajay");

        Map<String, String> toValueMap = new HashMap<>();
        toValueMap.put("firstName", "Ajay");

        Map<String, Object> toValueWrapper = new HashMap<>();
        toValueWrapper.put(Constants.TO_VALUE, toValueMap);

        List<Map<String, Object>> updatedValues = List.of(toValueWrapper);

        String updateValuesJson = new ObjectMapper().writeValueAsString(updatedValues);

        WfStatusEntity wfStatusEntity = new WfStatusEntity();
        wfStatusEntity.setUpdateFieldValues(updateValuesJson);
        wfStatusEntity.setCurrentStatus("true"); // dummy init

        List<WfStatusEntity> wfStatusEntityList = List.of(wfStatusEntity);

        // Mock WfStatusRepo
        when(wfStatusRepo.findByUserIdAndCurrentStatus(userId, true)).thenReturn(wfStatusEntityList);
        when(mapper.readValue(updateValuesJson, List.class)).thenReturn(updatedValues);
        SBApiResponse mockResponse = mock(SBApiResponse.class);

        when(storageService.uploadFile(any(File.class), anyString(), anyString())).thenReturn(mockResponse);

        // Perform
        assertDoesNotThrow(() -> service.processBulkUploadV1((HashMap<String, String>) input));

    }


    @Test
    void testProcessBulkUploadV1_EmptyFile() throws Exception {

        FileWriter writer = new FileWriter(tempFile);
        writer.write("");
        writer.close();

        Map<String, String> input = new HashMap<>();
        input.put(Constants.FILE_NAME, tempFile.getName());
        input.put(Constants.ROOT_ORG_ID, "rootOrg123");
        input.put(Constants.IDENTIFIER, "bulk123");

        assertDoesNotThrow(() -> service.processBulkUploadV1((HashMap<String, String>) input));
    }

    @Test
    void testProcessBulkUploadV1_InvalidEmailPhone() throws Exception {

        when(configuration.getCsvDelimiter()).thenReturn(',');
        when(configuration.getTagsDelimiter()).thenReturn(";");

        FileWriter writer = new FileWriter(tempFile);
        writer.write("Full Name,Email,Phone\n");
        writer.write("Jane Doe,,\n");
        writer.close();

        Map<String, String> input = new HashMap<>();
        input.put(Constants.FILE_NAME, tempFile.getName());
        input.put(Constants.ROOT_ORG_ID, "rootOrg123");
        input.put(Constants.IDENTIFIER, "bulk123");

        assertDoesNotThrow(() -> service.processBulkUploadV1((HashMap<String, String>) input));
    }

    @Test
    void testProcessBulkUploadV1_ExceptionFlow() {
        Map<String, String> input = new HashMap<>();
        input.put(Constants.FILE_NAME, "non_existing_file.csv");
        input.put(Constants.ROOT_ORG_ID, "rootOrg123");
        input.put(Constants.IDENTIFIER, "bulk123");

        assertDoesNotThrow(() -> service.processBulkUploadV1((HashMap<String, String>) input));
    }

    @Test
    void testProcessBulkUploadV1_SuccessfulCase_1() {
        Map<String, String> input = new HashMap<>();
        input.put(Constants.FILE_NAME, tempFile.getName());
        input.put(Constants.ROOT_ORG_ID, "rootOrg123");
        input.put(Constants.IDENTIFIER, "bulk123");


        when(configuration.getCsvDelimiter()).thenReturn(',');
        when(configuration.getTagsDelimiter()).thenReturn(";");
        when(configuration.getLmsServiceHost()).thenReturn("http://lms.example.com/");
        when(configuration.getLmsUserSearchEndPoint()).thenReturn("/user/search");

        // Sample headers and request object
        Map<String, String> headersValue = new HashMap<>();
        headersValue.put("Authorization", "Bearer token");

        Map<String, Object> requestObject = new HashMap<>();
        requestObject.put("query", "test");

// Prepare a fake response structure that matches expected hierarchy
        Map<String, Object> contentItem = new HashMap<>();
        contentItem.put(Constants.USER_ID, "user-123");
        contentItem.put(Constants.CHANNEL, "mdo-dept");
        contentItem.put(Constants.ROOT_ORG_ID, "root-org-1");

        List<Map<String, Object>> contents = List.of(contentItem);

        Map<String, Object> response = new HashMap<>();
        response.put(Constants.CONTENT, contents);

        Map<String, Object> result = new HashMap<>();
        result.put(Constants.RESPONSE, response);

        Map<String, Object> userSearchResult = new HashMap<>();
        userSearchResult.put(Constants.RESPONSE_CODE, "OK");
        userSearchResult.put(Constants.RESULT, result);

// Mocking requestServiceImpl.fetchResultUsingPost(...)
        when(requestService.fetchResultUsingPost(any(), any(), any(), any())).thenReturn(userSearchResult);

// Mock configuration values
        when(configuration.getUserBulkUpdateFolderName()).thenReturn("bulkFolder");
        when(configuration.getWorkflowCloudContainerName()).thenReturn("container");

// Mock response object and its nested fields
        SBApiResponse mockResponse = mock(SBApiResponse.class);
        SunbirdApiRespParam params = mock(SunbirdApiRespParam.class);

        when(mockResponse.getResponseCode()).thenReturn(HttpStatus.BAD_REQUEST);
        when(mockResponse.getParams()).thenReturn(params);
        when(params.getErrmsg()).thenReturn("Upload failed");

// Mock the storage service call
        when(storageService.uploadFile(any(File.class), anyString(), anyString())).thenReturn(mockResponse);

        // Perform
        assertDoesNotThrow(() -> service.processBulkUploadV1((HashMap<String, String>) input));

    }

}

