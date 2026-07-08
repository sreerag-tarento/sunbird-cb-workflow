package org.sunbird.workflow.service.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.service.BPWorkFlowService;
import org.sunbird.workflow.service.DomainWhiteListWorkFlowService;
import org.sunbird.workflow.service.UserProfileWfService;
import org.sunbird.workflow.service.UserRegistrationWfService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApplicationProcessingServiceImplV2 {

    @Autowired
    private UserProfileWfService userProfileWfService;

    @Autowired
    private Configuration configuration;
    @Autowired
    private RequestServiceImpl requestService;
    @Autowired
    private WfStatusRepo wfStatusRepo;

    @Autowired
    private UserRegistrationWfService userRegService;

    @Autowired
    private BPWorkFlowService bpWorkFlowService;

    @Autowired
    private DomainWhiteListWorkFlowService domainWhiteListWorkFlowService;

    Logger logger = LogManager.getLogger(ApplicationProcessingServiceImpl.class);

    public void processWfApplicationRequest(List<WfRequest> wfRequests, String serviceName, String userId, String userToken) {
        switch (serviceName) {
            // Or condition in case statement
            case Constants.PROFILE_SERVICE_NAME:
            case Constants.USER_PROFILE_FLAG_SERVICE:
                userProfileWfService.updateUserProfileV2(wfRequests, userId, userToken);
                break;
            case Constants.USER_REGISTRATION_SERVICE_NAME:
                wfRequests.forEach(wfRequest -> {
                    userRegService.processMessage(wfRequest);
                });
                break;
            case Constants.DOMAIN:
                wfRequests.forEach(wfRequest -> {
                    domainWhiteListWorkFlowService.processDomainRequest(wfRequest);
                });
            case Constants.BLENDED_PROGRAM_SERVICE_NAME:
            case Constants.ONE_STEP_MDO_APPROVAL:
            case Constants.ONE_STEP_PC_APPROVAL:
            case Constants.TWO_STEP_MDO_AND_PC_APPROVAL:
            case Constants.TWO_STEP_PC_AND_MDO_APPROVAL:
                wfRequests.forEach(wfRequest -> {
                    bpWorkFlowService.processWFRequest(wfRequest);
                });
                break;
            default:
                break;
        }
    }

    public void updateDepartmentToPortalDBs(List<WfRequest> wfRequests) {
        wfRequests.forEach(wfRequest -> {
            WfStatusEntity wfStatusEntity = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(), wfRequest.getWfId());
            if (Constants.PROFILE_SERVICE_NAME.equals(wfRequest.getServiceName())
                    && Constants.APPROVED_STATE.equals(wfStatusEntity.getCurrentStatus())) {
                for (HashMap<String, Object> updatedValues : wfRequest.getUpdateFieldValues()) {
                    if (ObjectUtils.isEmpty(updatedValues.get("fieldKey")))
                        continue;
                    if ("professionalDetails".equals(updatedValues.get("fieldKey"))) {
                        HashMap<String, Object> fromValue = (HashMap<String, Object>) updatedValues.get("fromValue");
                        HashMap<String, Object> toValue = (HashMap<String, Object>) updatedValues.get("toValue");
                        String key = toValue.entrySet().iterator().next().getKey();
                        if (!StringUtils.isEmpty(key) && key.equals("name")) {
                            String newDeptName = (String) toValue.entrySet().iterator().next().getValue();
                            String oldDeptName = (String) fromValue.entrySet().iterator().next().getValue();
                            HashMap<String, Object> departMentInfo = new HashMap<>();
                            departMentInfo.put("userId", wfRequest.getApplicationId());
                            departMentInfo.put("oldDeptName", oldDeptName);
                            departMentInfo.put("newDeptName", newDeptName);
                            departMentInfo.put("isActive", true);
                            departMentInfo.put("isBlocked", false);
                            updateUserDepartment(wfStatusEntity.getRootOrg(), wfStatusEntity.getOrg(),
                                    wfRequest.getApplicationId(), departMentInfo);
                        }
                    }
                }
            }
        });
    }

    public void updateUserDepartment(String rootOrg, String org, String wid, HashMap<String, Object> departmentInfo) {
        StringBuilder builder = new StringBuilder();
        builder.append(configuration.getLexCoreServiceHost()).append(configuration.getDepartmentUpdatePath());
        try {
            HashMap<String, String> headers = new HashMap<>();
            headers.put(Constants.ROOT_ORG_CONSTANT, rootOrg);
            headers.put("org", org);
            headers.put("wid", wid);
            requestService.fetchResultUsingPost(builder, departmentInfo, Map.class, headers);
        } catch (Exception e) {
            logger.error("Exception while posting the data in portal service: ", e);
        }
    }
}
