package org.sunbird.workflow.models;

public class AiAssessmentApprovalEvent {

    private WfRequest wfRequest;
    private String xAuthToken;

    public AiAssessmentApprovalEvent() {
    }

    public AiAssessmentApprovalEvent(WfRequest wfRequest, String xAuthToken) {
        this.wfRequest = wfRequest;
        this.xAuthToken = xAuthToken;
    }

    public WfRequest getWfRequest() {
        return wfRequest;
    }

    public void setWfRequest(WfRequest wfRequest) {
        this.wfRequest = wfRequest;
    }

    public String getXAuthToken() {
        return xAuthToken;
    }

    public void setXAuthToken(String xAuthToken) {
        this.xAuthToken = xAuthToken;
    }
}
