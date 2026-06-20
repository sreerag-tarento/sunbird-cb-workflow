package org.sunbird.workflow.service;

import java.util.Map;

public interface ContentSearchService {

    /**
     * Fetches batch details for all live blended programs from the composite v4 search service.
     *
     * @param limit  - number of results to fetch
     * @param offset - pagination offset
     * @return result map containing content (with batches) and count
     */
    Map<String, Object> getBlendedProgramBatchDetails(int limit, int offset);
}
