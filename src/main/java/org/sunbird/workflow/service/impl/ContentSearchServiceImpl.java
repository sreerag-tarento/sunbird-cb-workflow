package org.sunbird.workflow.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.service.ContentSearchService;

import org.apache.commons.collections.MapUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class ContentSearchServiceImpl implements ContentSearchService {

    private static final Logger logger = LoggerFactory.getLogger(ContentSearchServiceImpl.class);

    private final Configuration configuration;
    private final RequestServiceImpl requestServiceImpl;

    public ContentSearchServiceImpl(Configuration configuration, RequestServiceImpl requestServiceImpl) {
        this.configuration = configuration;
        this.requestServiceImpl = requestServiceImpl;
    }

    /**
     * Fetches a paginated list of live blended programs from the composite search service,
     * requesting only the batches field. Used by the sync job to discover all active batch IDs.
     * Returns the raw result map (containing count and content) or an empty map on failure.
     */
    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> getBlendedProgramBatchDetails(int limit, int offset) {
        try {
            StringBuilder url = new StringBuilder(configuration.getSbSearchServiceHost())
                    .append(configuration.getSbCompositeV4Search());
            Map<String, Object> filters = new HashMap<>();
            filters.put(Constants.CONTENT_TYPE_FIELD, Collections.singletonList(Constants.COURSE));
            filters.put(Constants.COURSE_CATEGORY, Collections.singletonList(Constants.BLENDED_PROGRAM_CATEGORY));
            filters.put(Constants.STATUS, Collections.singletonList(Constants.LIVE));
            filters.put(Constants.AVG_RATING, new HashMap<>());
            Map<String, Object> sortBy = new HashMap<>();
            sortBy.put(Constants.CREATED_ON, Constants.DESC);
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.FILTERS, filters);
            request.put(Constants.FIELDS, Collections.singletonList(Constants.BATCHES));
            request.put(Constants.FACETS, Collections.emptyList());
            request.put(Constants.QUERY, "");
            request.put(Constants.LIMIT, limit);
            request.put(Constants.OFFSET, offset);
            request.put(Constants.SORT_BY, sortBy);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(Constants.REQUEST, request);
            logger.info("ContentSearchService :: getBlendedProgramBatchDetails :: url={} limit={} offset={}", url, limit, offset);
            Map<String, Object> response = (Map<String, Object>) requestServiceImpl.fetchResultUsingPost(
                    url, requestBody, Map.class, null);
            if (MapUtils.isNotEmpty(response) && Constants.OK.equalsIgnoreCase((String) response.get(Constants.RESPONSE_CODE))) {
                Map<String, Object> result = (Map<String, Object>) response.get(Constants.RESULT);
                if (MapUtils.isNotEmpty(result)) {
                    logger.info("ContentSearchService :: getBlendedProgramBatchDetails :: responseCode=OK count={}", result.get("count"));
                    return result;
                }
            }
            logger.warn("ContentSearchService :: getBlendedProgramBatchDetails :: responseCode={} empty or failed response",
                    MapUtils.isNotEmpty(response) ? response.get(Constants.RESPONSE_CODE) : "null");
        } catch (Exception e) {
            logger.error("ContentSearchService :: getBlendedProgramBatchDetails :: limit={} offset={} error", limit, offset, e);
        }
        return Collections.emptyMap();
    }
}
