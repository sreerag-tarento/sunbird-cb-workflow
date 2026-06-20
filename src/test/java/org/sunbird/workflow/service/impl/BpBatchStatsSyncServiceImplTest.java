package org.sunbird.workflow.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.WorkflowRedisCacheMgr;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.service.ContentSearchService;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BpBatchStatsSyncServiceImplTest {

    @Mock
    private ContentSearchService contentSearchService;

    @Mock
    private WorkflowRedisCacheMgr workflowRedisCacheMgr;

    @Mock
    private Configuration configuration;

    @InjectMocks
    private BpBatchStatsSyncServiceImpl syncService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(configuration.getBpBatchStatsSyncDbChunkSize()).thenReturn(50);
        when(configuration.getBpBatchStatsSyncPageSize()).thenReturn(100);
    }

    @Test
    void syncBatchCountsToRedis_emptySearchResult_returnsZeroBatchesProcessed() {
        when(contentSearchService.getBlendedProgramBatchDetails(anyInt(), anyInt()))
                .thenReturn(Collections.emptyMap());
        Response response = syncService.syncBatchCountsToRedis();
        assertEquals(0, response.get("batchesProcessed"));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        verify(workflowRedisCacheMgr, never()).bulkInitBatchStats(any());
    }

    @Test
    void syncBatchCountsToRedis_singlePageWithBatches_delegatesChunkToCacheMgr() {
        List<Map<String, Object>> programs = List.of(
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "batch-001"))),
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "batch-002")))
        );
        Map<String, Object> result = new HashMap<>();
        result.put("count", 2);
        result.put(Constants.CONTENT, programs);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 0)).thenReturn(result);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 100)).thenReturn(Collections.emptyMap());
        Response response = syncService.syncBatchCountsToRedis();
        assertEquals(2, response.get("batchesProcessed"));
        assertEquals(HttpStatus.OK, response.get(Constants.STATUS));
        verify(workflowRedisCacheMgr).bulkInitBatchStats(List.of("batch-001", "batch-002"));
    }

    @Test
    void syncBatchCountsToRedis_programsWithNoBatchesField_skipsThosePrograms() {
        List<Map<String, Object>> programs = new ArrayList<>();
        programs.add(Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "batch-001"))));
        programs.add(new HashMap<>());
        Map<String, Object> result = new HashMap<>();
        result.put("count", 2);
        result.put(Constants.CONTENT, programs);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 0)).thenReturn(result);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 100)).thenReturn(Collections.emptyMap());
        Response response = syncService.syncBatchCountsToRedis();
        assertEquals(1, response.get("batchesProcessed"));
        verify(workflowRedisCacheMgr).bulkInitBatchStats(List.of("batch-001"));
    }

    @Test
    void syncBatchCountsToRedis_batchesWithNullOrBlankBatchId_filtersThemOut() {
        Map<String, Object> batchWithNull = new HashMap<>();
        batchWithNull.put(Constants.BATCH_ID, null);
        Map<String, Object> batchWithBlank = new HashMap<>();
        batchWithBlank.put(Constants.BATCH_ID, "   ");
        List<Map<String, Object>> programs = List.of(
                Map.of(Constants.BATCHES, Arrays.asList(
                        batchWithNull,
                        batchWithBlank,
                        Map.of(Constants.BATCH_ID, "batch-valid")
                ))
        );
        Map<String, Object> result = new HashMap<>();
        result.put("count", 1);
        result.put(Constants.CONTENT, programs);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 0)).thenReturn(result);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 100)).thenReturn(Collections.emptyMap());
        Response response = syncService.syncBatchCountsToRedis();
        assertEquals(1, response.get("batchesProcessed"));
        verify(workflowRedisCacheMgr).bulkInitBatchStats(List.of("batch-valid"));
    }

    @Test
    void syncBatchCountsToRedis_multiplePages_paginatesUntilResultsExhausted() {
        List<Map<String, Object>> page1 = List.of(
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "batch-P1"))),
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "batch-P2")))
        );
        Map<String, Object> result1 = new HashMap<>();
        result1.put("count", 102);
        result1.put(Constants.CONTENT, page1);
        List<Map<String, Object>> page2 = List.of(
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "batch-P3")))
        );
        Map<String, Object> result2 = new HashMap<>();
        result2.put("count", 102);
        result2.put(Constants.CONTENT, page2);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 0)).thenReturn(result1);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 100)).thenReturn(result2);
        Response response = syncService.syncBatchCountsToRedis();
        assertEquals(3, response.get("batchesProcessed"));
        verify(contentSearchService, times(2)).getBlendedProgramBatchDetails(anyInt(), anyInt());
    }

    @Test
    void syncBatchCountsToRedis_chunkSize2_callsBulkInitCorrectNumberOfTimes() {
        when(configuration.getBpBatchStatsSyncDbChunkSize()).thenReturn(2);
        List<Map<String, Object>> programs = List.of(
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "b1"))),
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "b2"))),
                Map.of(Constants.BATCHES, List.of(Map.of(Constants.BATCH_ID, "b3")))
        );
        Map<String, Object> result = new HashMap<>();
        result.put("count", 3);
        result.put(Constants.CONTENT, programs);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 0)).thenReturn(result);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 100)).thenReturn(Collections.emptyMap());
        syncService.syncBatchCountsToRedis();
        verify(workflowRedisCacheMgr, times(2)).bulkInitBatchStats(any());
        verify(workflowRedisCacheMgr).bulkInitBatchStats(List.of("b1", "b2"));
        verify(workflowRedisCacheMgr).bulkInitBatchStats(List.of("b3"));
    }

    @Test
    void syncBatchCountsToRedis_programWithMultipleBatches_allBatchIdsCollected() {
        List<Map<String, Object>> programs = List.of(
                Map.of(Constants.BATCHES, List.of(
                        Map.of(Constants.BATCH_ID, "batch-A"),
                        Map.of(Constants.BATCH_ID, "batch-B"),
                        Map.of(Constants.BATCH_ID, "batch-C")
                ))
        );
        Map<String, Object> result = new HashMap<>();
        result.put("count", 1);
        result.put(Constants.CONTENT, programs);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 0)).thenReturn(result);
        when(contentSearchService.getBlendedProgramBatchDetails(100, 100)).thenReturn(Collections.emptyMap());
        Response response = syncService.syncBatchCountsToRedis();
        assertEquals(3, response.get("batchesProcessed"));
        verify(workflowRedisCacheMgr).bulkInitBatchStats(List.of("batch-A", "batch-B", "batch-C"));
    }
}
