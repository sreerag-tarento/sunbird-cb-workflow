package org.sunbird.workflow.service;

import java.util.List;
import java.util.Map;

import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;

public interface UserProfileWfService {

    public void updateUserProfile(WfRequest wfRequest);

    public void updateUserProfileV2(List<WfRequest> wfRequest, String userId, String userToken);

    public List<Map<String, Object>> enrichUserData(Map<String, List<WfStatusEntity>> statusEntities, String rootOrg);

    public void updateUserProfileForBulkUpload(WfRequest wfRequest);
}
