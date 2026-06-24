package org.sunbird.workflow.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.service.BpBatchStatsSyncService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BpBatchStatsSyncControllerTest {

    @Mock
    private BpBatchStatsSyncService bpBatchStatsSyncService;

    private BpBatchStatsSyncController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new BpBatchStatsSyncController(bpBatchStatsSyncService);
    }

    @Test
    void syncBatchCountsToRedis_delegatesToServiceAndReturnsOk() {
        Response serviceResponse = new Response();
        serviceResponse.put("batchesProcessed", 42);
        serviceResponse.put(Constants.STATUS, HttpStatus.OK);
        when(bpBatchStatsSyncService.syncBatchCountsToRedis()).thenReturn(serviceResponse);
        ResponseEntity<Response> entity = controller.syncBatchCountsToRedis();
        assertEquals(HttpStatus.OK, entity.getStatusCode());
        assertNotNull(entity.getBody());
        assertEquals(42, entity.getBody().get("batchesProcessed"));
        verify(bpBatchStatsSyncService).syncBatchCountsToRedis();
    }

    @Test
    void syncBatchCountsToRedis_usesHttpStatusFromServiceResponse() {
        Response serviceResponse = new Response();
        serviceResponse.put(Constants.STATUS, HttpStatus.INTERNAL_SERVER_ERROR);
        when(bpBatchStatsSyncService.syncBatchCountsToRedis()).thenReturn(serviceResponse);
        ResponseEntity<Response> entity = controller.syncBatchCountsToRedis();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, entity.getStatusCode());
    }

    @Test
    void syncBatchCountsToRedis_zeroBatchesProcessed_stillReturnsOk() {
        Response serviceResponse = new Response();
        serviceResponse.put("batchesProcessed", 0);
        serviceResponse.put(Constants.STATUS, HttpStatus.OK);
        when(bpBatchStatsSyncService.syncBatchCountsToRedis()).thenReturn(serviceResponse);
        ResponseEntity<Response> entity = controller.syncBatchCountsToRedis();
        assertEquals(HttpStatus.OK, entity.getStatusCode());
        assertEquals(0, entity.getBody().get("batchesProcessed"));
    }
}
