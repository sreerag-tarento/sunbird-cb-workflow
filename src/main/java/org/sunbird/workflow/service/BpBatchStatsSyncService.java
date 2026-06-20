package org.sunbird.workflow.service;

import org.sunbird.workflow.models.Response;

public interface BpBatchStatsSyncService {

    /**
     * Fetches all live blended programs from the search service, extracts batch IDs,
     * and restores the enrollment stats in Redis for any batch whose key has expired.
     * Batches whose key still exists in Redis are left untouched.
     *
     * @return Response containing count of batches initialised
     */
    Response syncBatchCountsToRedis();
}
