package org.sunbird.workflow.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.models.Response;
import org.sunbird.workflow.models.SearchCriteria;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.service.OrganisationWorkFlowService;
import org.sunbird.workflow.service.Workflowservice;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
public class OrganisationWorkFlowServiceImpl implements OrganisationWorkFlowService {

    @Autowired
    @Qualifier("workflowServiceImpl")
    private Workflowservice workflowService;

    @Autowired
    private Configuration configuration;

    @Autowired
    private RequestServiceImpl requestServiceImpl;

    private Logger logger = LoggerFactory.getLogger(OrganisationWorkFlowServiceImpl.class);

    /**
     * Service method to handle the creation of Users.
     *
     * @param rootOrg   - Root Organization Name ex: "igot"
     * @param org       - Organization name ex: "dopt"
     * @param wfRequest - WorkFlow request which needs to be processed.
     * @return - Return the response of success/failure after processing the request.
     */
    @Override
    public Response createOrgWorkFlow(String rootOrg, String org, WfRequest wfRequest) {
        Response response = new Response();
        wfRequest.getUpdateFieldValues().forEach(updateFieldValuesMap -> updateFieldValuesMap.forEach((updateFieldValuesKey, updateFieldValuesValue) -> {
            if (Constants.EMAIL.equalsIgnoreCase(updateFieldValuesKey) && isUserDetailExists(Constants.EMAIL, (String)updateFieldValuesValue)) {
                response.put(Constants.ERROR_MESSAGE, Constants.EMAIL_EXIST_ERROR);
                response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            } else if (Constants.PHONE.equalsIgnoreCase(updateFieldValuesKey) && isUserDetailExists(Constants.PHONE, (String)updateFieldValuesValue)) {
                response.put(Constants.ERROR_MESSAGE, Constants.PHONE_NUMBER_EXIST_ERROR);
                response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            } else if (Constants.TO_VALUE.equalsIgnoreCase(updateFieldValuesKey)) {
                Map<String, Object> toValueMap = (Map<String, Object>) updateFieldValuesValue;
                toValueMap.forEach((toValueKey, toValueValue) -> {
                    if (Constants.ORGANISATION_SERVICE_NAME.equalsIgnoreCase(toValueKey) && isOrgDetailExists(Constants.ORGANIZATION_NAME, (String)toValueValue)) {
                        response.put(Constants.ERROR_MESSAGE, Constants.ORGANIZATION_EXIST_ERROR);
                        response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
                    }
                });
            }
        }));
        if (!HttpStatus.BAD_REQUEST.equals(response.get(Constants.STATUS))) {
            Response finalResponse;
            finalResponse = workflowService.workflowTransition(rootOrg, org, wfRequest);
            finalResponse.put(Constants.STATUS, HttpStatus.OK);
            return finalResponse;
        } else {
            return response;
        }

    }

    @Override
    public Response updateOrgWorkFlow(String rootOrg, String org, WfRequest wfRequest) {
        Response response = workflowService.workflowTransition(rootOrg, org, wfRequest);
        return response;
    }

    @Override
    public Response readOrgWFApplication(String rootOrg, String org, String wfId, String applicationId) {
        Response response = workflowService.getWfApplication(rootOrg, org, wfId, applicationId);
        return response;
    }

    @Override
    public Response orgSearch(String rootOrg, String org, SearchCriteria criteria) {
        Response response = workflowService.applicationsSearch(rootOrg, org, criteria, Constants.ORG_SEARCH_ENABLED);
        return response;
    }

    /**
     * This method is responsible to check if the data with respect to the users already exists or not.
     *
     * @param key   - key is the parameter which is received in the wfRequest.
     * @param value - value corresponding to the parameter received in the wfRequest.
     * @return - Returns a boolean value true if the data already exists else false.
     */
    public boolean isUserDetailExists(String key, String value) {
        Map<String, Object> reqMap = new HashMap<>();
        reqMap.put(Constants.FILTERS, Collections.singletonMap(key, value));
        Map<String, Object> requestObj = new HashMap<>();
        requestObj.put(Constants.REQUEST, reqMap);
        HashMap<String, String> headersValue = new HashMap<>();
        headersValue.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        try {
            StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
            builder.append(configuration.getLmsUserSearchEndPoint());
            Map<String, Object> response = (Map<String, Object>) requestServiceImpl
                    .fetchResultUsingPost(builder, requestObj, Map.class, headersValue);
            if (response != null && Constants.OK.equalsIgnoreCase((String)response.get(Constants.RESPONSE_CODE))) {
                Map<String, Object> map = (Map<String, Object>) response.get(Constants.RESULT);
                if (map.get(Constants.RESPONSE) != null) {
                    Map<String, Object> responseObj = (Map<String, Object>) map.get(Constants.RESPONSE);
                    int count = (int) responseObj.get(Constants.COUNT);
                    return count != 0;
                }
            }
        } catch (Exception e) {
            logger.info("There is a error occured while searching for the user details : " + e);
        }
        return true;
    }

    public boolean isOrgDetailExists(String key, String value) {
        Map<String, Object> reqMap = new HashMap<>();
        reqMap.put(Constants.FILTERS, Collections.singletonMap(key, value));
        Map<String, Object> requestObj = new HashMap<>();
        requestObj.put(Constants.REQUEST, reqMap);
        HashMap<String, String> headersValue = new HashMap<>();
        headersValue.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
        try {
            StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
            builder.append(configuration.getLmsOrgSearchEndPoint());
            Map<String, Object> response = (Map<String, Object>) requestServiceImpl
                    .fetchResultUsingPost(builder, requestObj, Map.class, headersValue);
            if (response != null && Constants.OK.equalsIgnoreCase((String)response.get(Constants.RESPONSE_CODE))) {
                Map<String, Object> map = (Map<String, Object>) response.get(Constants.RESULT);
                if (map.get(Constants.RESPONSE) != null) {
                    Map<String, Object> responseObj = (Map<String, Object>) map.get(Constants.RESPONSE);
                    int count = (int) responseObj.get(Constants.COUNT);
                    return count != 0;
                }
            }
        } catch (Exception e) {
            logger.info("There is a error occured while searching for the Org details : " + e);
        }
        return true;
    }
}