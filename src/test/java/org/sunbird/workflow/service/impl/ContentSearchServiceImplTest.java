package org.sunbird.workflow.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContentSearchServiceImplTest {

    @Mock
    private Configuration configuration;

    @Mock
    private RequestServiceImpl requestServiceImpl;

    private ContentSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ContentSearchServiceImpl(configuration, requestServiceImpl);
        when(configuration.getSbSearchServiceHost()).thenReturn("http://localhost:6001");
        when(configuration.getSbCompositeV4Search()).thenReturn("/v4/search");
    }

    @Test
    void getBlendedProgramBatchDetails_success_returnsResultMap() {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("count", 5);
        resultMap.put(Constants.CONTENT, List.of(Map.of("identifier", "prog-1")));
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put(Constants.RESPONSE_CODE, Constants.OK);
        responseMap.put(Constants.RESULT, resultMap);
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenReturn(responseMap);
        Map<String, Object> result = service.getBlendedProgramBatchDetails(10, 0);
        assertFalse(result.isEmpty());
        assertEquals(5, result.get("count"));
    }

    @Test
    void getBlendedProgramBatchDetails_failedResponseCode_returnsEmptyMap() {
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put(Constants.RESPONSE_CODE, "ERROR");
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenReturn(responseMap);
        assertTrue(service.getBlendedProgramBatchDetails(10, 0).isEmpty());
    }

    @Test
    void getBlendedProgramBatchDetails_nullResponse_returnsEmptyMap() {
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenReturn(null);
        assertTrue(service.getBlendedProgramBatchDetails(10, 0).isEmpty());
    }

    @Test
    void getBlendedProgramBatchDetails_emptyResultMapInResponse_returnsEmptyMap() {
        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put(Constants.RESPONSE_CODE, Constants.OK);
        responseMap.put(Constants.RESULT, Collections.emptyMap());
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenReturn(responseMap);
        assertTrue(service.getBlendedProgramBatchDetails(10, 0).isEmpty());
    }

    @Test
    void getBlendedProgramBatchDetails_exceptionThrown_returnsEmptyMap() {
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenThrow(new RuntimeException("Search service down"));
        assertTrue(service.getBlendedProgramBatchDetails(10, 0).isEmpty());
    }

    @Test
    void getBlendedProgramBatchDetails_buildsCorrectLimitOffsetInRequestBody() {
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenReturn(null);
        service.getBlendedProgramBatchDetails(25, 50);
        verify(requestServiceImpl).fetchResultUsingPost(
                argThat(url -> url.toString().equals("http://localhost:6001/v4/search")),
                argThat(body -> {
                    Map<?, ?> req = (Map<?, ?>) ((Map<?, ?>) body).get(Constants.REQUEST);
                    return req != null
                            && Integer.valueOf(25).equals(req.get(Constants.LIMIT))
                            && Integer.valueOf(50).equals(req.get(Constants.OFFSET));
                }),
                eq(Map.class),
                isNull()
        );
    }

    @Test
    void getBlendedProgramBatchDetails_requestBodyContainsBlendedProgramFilters() {
        when(requestServiceImpl.fetchResultUsingPost(any(), any(), eq(Map.class), isNull()))
                .thenReturn(null);
        service.getBlendedProgramBatchDetails(10, 0);
        verify(requestServiceImpl).fetchResultUsingPost(
                any(),
                argThat(body -> {
                    Map<?, ?> req = (Map<?, ?>) ((Map<?, ?>) body).get(Constants.REQUEST);
                    if (req == null) return false;
                    Map<?, ?> filters = (Map<?, ?>) req.get(Constants.FILTERS);
                    if (filters == null) return false;
                    boolean hasContentType = filters.containsKey(Constants.CONTENT_TYPE_FIELD);
                    boolean hasCategory = filters.containsKey(Constants.COURSE_CATEGORY);
                    boolean hasFields = req.containsKey(Constants.FIELDS);
                    return hasContentType && hasCategory && hasFields;
                }),
                eq(Map.class),
                isNull()
        );
    }
}
