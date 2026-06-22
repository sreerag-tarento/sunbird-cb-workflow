package org.sunbird.workflow.service.impl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.WorkflowRedisCacheMgr;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.service.BpBatchStatsSyncService;
import org.sunbird.workflow.service.ContentSearchService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Service
public class BpBatchStatsSyncServiceImpl implements BpBatchStatsSyncService {

    private static final Logger logger = LoggerFactory.getLogger(BpBatchStatsSyncServiceImpl.class);

    private final ContentSearchService contentSearchService;
    private final WorkflowRedisCacheMgr workflowRedisCacheMgr;
    private final Configuration configuration;

    public BpBatchStatsSyncServiceImpl(ContentSearchService contentSearchService,
                                       WorkflowRedisCacheMgr workflowRedisCacheMgr,
                                       Configuration configuration) {
        this.contentSearchService = contentSearchService;
        this.workflowRedisCacheMgr = workflowRedisCacheMgr;
        this.configuration = configuration;
    }

    /**
     * Entry point for the batch stats sync job.
     * Fetches all live blended program batch IDs, partitions them into configurable chunks,
     * and delegates each chunk to WorkflowRedisCacheMgr for cache warm-up.
     * Keys that are still live in Redis are skipped; only expired keys are re-initialised.
     */
    @Override
    public Response syncBatchCountsToRedis() {
        logger.info("BpBatchStatsSyncService :: syncBatchCountsToRedis :: started");
        List<String> batchIds = fetchAllBatchIds();
        int chunkSize = configuration.getBpBatchStatsSyncDbChunkSize();
        int totalChunks = (int) Math.ceil((double) batchIds.size() / chunkSize);
        logger.info("BpBatchStatsSyncService :: syncBatchCountsToRedis :: totalBatchIds={} chunkSize={} totalChunks={}", batchIds.size(), chunkSize, totalChunks);
        IntStream.range(0, totalChunks)
                .mapToObj(i -> batchIds.subList(i * chunkSize, Math.min((i + 1) * chunkSize, batchIds.size())))
                .forEach(workflowRedisCacheMgr::bulkInitBatchStats);
        logger.info("BpBatchStatsSyncService :: syncBatchCountsToRedis :: completed batchesProcessed={}", batchIds.size());
        Response response = new Response();
        response.put("batchesProcessed", batchIds.size());
        response.put(Constants.STATUS, HttpStatus.OK);
        return response;
    }

    /**
     * Paginates through all live blended programs via the composite search service
     * and collects every batch ID found in each program's batches field.
     */
    private List<String> fetchAllBatchIds() {
        List<String> allBatchIds = new ArrayList<>();
        int offset = 0;
        int totalCount = Integer.MAX_VALUE;
        int pageSize = configuration.getBpBatchStatsSyncPageSize();
        while (offset < totalCount) {
            Map<String, Object> result = contentSearchService.getBlendedProgramBatchDetails(pageSize, offset);
            totalCount = extractTotalCount(result);
            List<Map<String, Object>> programs = extractPrograms(result);
            logger.debug("BpBatchStatsSyncService :: fetchAllBatchIds :: offset={} totalCount={} programsOnPage={}", offset, totalCount, programs.size());
            if (programs.isEmpty()) break;
            allBatchIds.addAll(extractBatchIds(programs));
            offset += pageSize;
        }
        logger.info("BpBatchStatsSyncService :: fetchAllBatchIds :: totalBatchIds={}", allBatchIds.size());
        return allBatchIds;
    }

    /**
     * Extracts the program list from a composite search result.
     * Returns an empty list when the result map is absent or the content field is missing.
     */
    private List<Map<String, Object>> extractPrograms(Map<String, Object> result) {
        if (MapUtils.isEmpty(result)) return Collections.emptyList();
        List<Map<String, Object>> programs = (List<Map<String, Object>>) result.get(Constants.CONTENT);
        return CollectionUtils.isNotEmpty(programs) ? programs : Collections.emptyList();
    }

    /**
     * Reads the total record count from a composite search result.
     * Returns 0 on an empty result to terminate the pagination loop.
     */
    private int extractTotalCount(Map<String, Object> result) {
        if (MapUtils.isEmpty(result)) return 0;
        return ((Number) result.getOrDefault("count", 0)).intValue();
    }

    /**
     * Flattens each program's batches list into a stream of non-blank batch IDs.
     * Programs with a missing or empty batches field are skipped entirely.
     */
    private List<String> extractBatchIds(List<Map<String, Object>> programs) {
        return programs.stream()
                .filter(p -> CollectionUtils.isNotEmpty((Collection<?>) p.get(Constants.BATCHES)))
                .flatMap(p -> ((List<Map<String, Object>>) p.get(Constants.BATCHES)).stream())
                .map(b -> (String) b.get(Constants.BATCH_ID))
                .filter(StringUtils::isNotBlank)
                .toList();
    }
}
