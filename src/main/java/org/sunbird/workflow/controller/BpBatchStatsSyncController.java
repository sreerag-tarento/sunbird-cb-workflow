package org.sunbird.workflow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.service.BpBatchStatsSyncService;

@RestController
@RequestMapping("/v1/blendedprogram/batch/stats")
public class BpBatchStatsSyncController {

    private final BpBatchStatsSyncService bpBatchStatsSyncService;

    public BpBatchStatsSyncController(BpBatchStatsSyncService bpBatchStatsSyncService) {
        this.bpBatchStatsSyncService = bpBatchStatsSyncService;
    }

    /**
     * Triggers a Redis warm-up for all live blended program batches whose stats key has expired.
     * Fetches batch IDs from the search service, processes them in configurable chunks,
     * and re-initialises the Redis hash only for expired keys. Live keys are left untouched.
     */
    @PostMapping("/sync")
    public ResponseEntity<Response> syncBatchCountsToRedis() {
        Response response = bpBatchStatsSyncService.syncBatchCountsToRedis();
        return new ResponseEntity<>(response, (HttpStatus) response.get(Constants.STATUS));
    }
}
