package org.sunbird.workflow.service;

import java.util.Map;

/**
 * @author mahesh.vakkund
 */
public interface ContentReadService {

    /**
     * @param courseId - CourseId of the blended program.
     * @return - Map containing wfApprovalType and primaryCategory
     */
    public Map<String, Object> getServiceNameDetails(String courseId);
    public String getRootOrgId(String courseId);
}
