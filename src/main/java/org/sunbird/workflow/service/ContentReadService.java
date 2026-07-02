package org.sunbird.workflow.service;

/**
 * @author mahesh.vakkund
 */
public interface ContentReadService {

    /**
     * @param courseId - CourseId of the blended program.
     * @return - serviceName which is used to fetch the wf enroll configuration json.
     */
    public String getServiceNameDetails(String courseId);
    public String getRootOrgId(String courseId);
}
