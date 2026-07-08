package org.sunbird.workflow.service;

import org.sunbird.workflow.models.Response;

import java.util.Map;

public interface WorkFlowServiceV2 {
    Response workflowTransition(String rootOrg, String org, Map<String, Object> request, String userToken);
}
