package org.sunbird.workflow.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;

import java.util.*;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentReadServiceImplTest {

    @InjectMocks
    private ContentReadServiceImpl service;

    @Mock
    private Configuration configuration;

    @Mock
    private RequestServiceImpl requestService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetServiceNameDetails_Success() {
        String courseId = "course123";

        when(configuration.getContentServiceHost()).thenReturn("http://host/");
        when(configuration.getContentReadEndPoint()).thenReturn("content/");

        Map<String, Object> contentMap = new HashMap<>();
        contentMap.put("wfApprovalType", "someService");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put(Constants.CONTENT, contentMap);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put(Constants.RESPONSE_CODE, Constants.OK);
        responseMap.put(Constants.RESULT, resultMap);

        when(requestService.fetchResultUsingGet(any())).thenReturn(responseMap);

        String result = service.getServiceNameDetails(courseId);

        assertEquals("someService", result);
    }

    @Test
    void testGetServiceNameDetails_NullResponse() {
        String courseId = "course123";

        when(configuration.getContentServiceHost()).thenReturn("http://host/");
        when(configuration.getContentReadEndPoint()).thenReturn("content/");

        when(requestService.fetchResultUsingGet(any())).thenReturn(null);

        String result = service.getServiceNameDetails(courseId);

        assertNull(result);
    }

    @Test
    void testGetServiceNameDetails_Exception() {
        String courseId = "course123";

        when(configuration.getContentServiceHost()).thenReturn("http://host/");
        when(configuration.getContentReadEndPoint()).thenReturn("content/");

        when(requestService.fetchResultUsingGet(any())).thenThrow(new RuntimeException("error"));

        String result = service.getServiceNameDetails(courseId);

        assertNull(result);
    }

    @Test
    void testGetRootOrgId_Success() {
        String courseId = "course123";

        when(configuration.getContentServiceHost()).thenReturn("http://host/");
        when(configuration.getContentReadEndPoint()).thenReturn("content/");

        Map<String, Object> contentMap = new HashMap<>();
        contentMap.put("createdFor", List.of("rootOrg123"));

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put(Constants.CONTENT, contentMap);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put(Constants.RESPONSE_CODE, Constants.OK);
        responseMap.put(Constants.RESULT, resultMap);

        when(requestService.fetchResultUsingGet(any())).thenReturn(responseMap);

        String result = service.getRootOrgId(courseId);

        assertEquals("rootOrg123", result);
    }

    @Test
    void testGetRootOrgId_NullResponse() {
        String courseId = "course123";

        when(configuration.getContentServiceHost()).thenReturn("http://host/");
        when(configuration.getContentReadEndPoint()).thenReturn("content/");

        when(requestService.fetchResultUsingGet(any())).thenReturn(null);

        String result = service.getRootOrgId(courseId);

        assertNull(result);
    }

    @Test
    void testGetRootOrgId_Exception() {
        String courseId = "course123";

        when(configuration.getContentServiceHost()).thenReturn("http://host/");
        when(configuration.getContentReadEndPoint()).thenReturn("content/");

        when(requestService.fetchResultUsingGet(any())).thenThrow(new RuntimeException("error"));

        String result = service.getRootOrgId(courseId);

        assertNull(result);
    }
}
