package org.sunbird.workflow.service.impl;


import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import org.springframework.util.ObjectUtils;

import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.exception.ApplicationException;
import org.sunbird.workflow.exception.BadRequestException;
import org.sunbird.workflow.exception.InvalidDataInputException;
import org.sunbird.workflow.models.*;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.producer.Producer;
import org.sunbird.workflow.service.BPWorkFlowService;
import org.sunbird.workflow.service.ContentReadService;
import org.sunbird.workflow.service.Workflowservice;
import org.sunbird.workflow.utils.CassandraOperation;


import com.fasterxml.jackson.core.type.TypeReference;

import java.util.UUID;
@Service
public class BPWorkFlowServiceImpl implements BPWorkFlowService {

    private Logger logger = LoggerFactory.getLogger(BPWorkFlowServiceImpl.class);

    @Autowired
    private Workflowservice workflowService;

    @Autowired
    private CassandraOperation cassandraOperation;

    @Autowired
    private RequestServiceImpl requestServiceImpl;

    @Autowired
    private Configuration configuration;

    @Autowired
    private WfStatusRepo wfStatusRepo;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private Producer producer;

    @Autowired
    private ContentReadService contentReadService;

    @Override
    public Response enrolBPWorkFlow(String rootOrg, String org, WfRequest wfRequest) {
        validateWfRequestMultilevelEnrol(wfRequest);
        Map<String, Object> courseBatchDetails = getCurrentBatchAttributes(wfRequest.getApplicationId(),
                wfRequest.getCourseId());
        String serviceName = contentReadService.getServiceNameDetails(wfRequest.getCourseId());
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = Constants.BLENDED_PROGRAM_SERVICE_NAME;
        }
        int totalUserEnrolCount = getTotalUserEnrolCountForBatch(wfRequest.getApplicationId());
        int totalApprovedUserCount = getTotalApprovedUserCount(wfRequest);
        boolean enrolAccess = validateBatchEnrolment(courseBatchDetails, totalApprovedUserCount, totalUserEnrolCount,
                Constants.BP_ENROLL_STATE);
        if (!enrolAccess) {
            Response response = new Response();
            response.put(Constants.ERROR_MESSAGE, configuration.getBatchFullMesg());
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        if (scheduleConflictCheck(wfRequest)) {
            Response response = new Response();
            response.put(Constants.ERROR_MESSAGE,"Not allowed to enroll the user to the Blended Program since there is a schedule conflict");
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        wfRequest.setServiceName(Constants.BLENDED_PROGRAM_SERVICE_NAME);
        wfRequest.setBatchName((String) courseBatchDetails.get(Constants.BATCH_NAME));
        wfRequest.setBatchStartDate((Date) courseBatchDetails.get(Constants.START_DATE));
        Response response = saveEnrollUserIntoWfStatus(rootOrg, org, wfRequest);
        producer.push(configuration.getWorkflowApplicationTopic(), wfRequest);
        return response;
    }

    @Override
    public Response updateBPWorkFlow(String rootOrg, String org, WfRequest wfRequest,String userId,String role) {
        logger.info("Workflow request Body received: " + wfRequest);
        Response response = new Response();
        Map<String, Object> batchDetailsMap = new HashMap<>();
        String validationError = validateBatchUserRequestAccess(wfRequest, batchDetailsMap);
        wfRequest.setBatchName((String) batchDetailsMap.get(Constants.BATCH_NAME));
        wfRequest.setBatchStartDate((Date) batchDetailsMap.get(Constants.START_DATE));
        if (Constants.BATCH_START_DATE_ERROR.equals(validationError)) {
            response.put(Constants.ERROR_MESSAGE, configuration.getBatchInProgressMessage());
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        if (Constants.BATCH_SIZE_ERROR.equals(validationError)) {
            response.put(Constants.ERROR_MESSAGE, configuration.getBatchFullMesg());
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        if (scheduleConflictCheck(wfRequest)) {
            wfRequest.setAction(Constants.REJECT);
            wfRequest.setComment(configuration.getConflictRejectReason());
            workflowService.workflowTransition(rootOrg, org, wfRequest);
            response.put(Constants.ERROR_MESSAGE, configuration.getConflictRejectReason());
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        return workflowService.workflowTransition(rootOrg, org, wfRequest, userId,role);
    }

    @Override
    public Response readBPWFApplication(String rootOrg, String org, String wfId, String applicationId) {
        Response response = workflowService.getWfApplication(rootOrg, org, wfId, applicationId);
        return response;
    }

    @Override
    public Response blendedProgramSearch(String rootOrg, String org, SearchCriteria criteria) {
        Response response = workflowService.applicationsSearch(rootOrg, org, criteria,
                Constants.BLENDED_PROGRAM_SEARCH_ENABLED);
        return response;
    }

    public Response bpPCSearch(String rootOrg, String org, SearchCriteriaV2 criteria) {
        Response response = workflowService.appsPCSearchV2(rootOrg, org, criteria);
        return response;
    }

    public Response bpMDOSearch(String rootOrg, String org, SearchCriteriaV2 criteria) {
        Response response = workflowService.appsPCSearchV2(rootOrg, org, criteria);
        return response;
    }

    @Override
    public Response blendedProgramStatusCount(SearchCriteria criteria) {
        Response response = workflowService.statusCountOnApplicationId( criteria);
        return response;
    }

    @Override
    public void updateEnrolmentDetails(WfRequest wfRequest) {
        Map<String, Object> courseBatchDetails = getCurrentBatchAttributes(wfRequest.getApplicationId(),
                wfRequest.getCourseId());
        int totalApprovedUserCount = getTotalApprovedUserCount(wfRequest);
        boolean enrolAccess = validateBatchEnrolment(courseBatchDetails, totalApprovedUserCount, 0,
                Constants.BP_UPDATE_STATE);
        if (enrolAccess) {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(Constants.USER_ID, wfRequest.getUserId());
            requestBody.put(Constants.BATCH_ID, wfRequest.getApplicationId());
            requestBody.put(Constants.COURSE_ID, wfRequest.getCourseId());
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.REQUEST, requestBody);
            HashMap<String, String> headersValue = new HashMap<>();
            headersValue.put("Content-Type", "application/json");
            try {
                StringBuilder builder = new StringBuilder(configuration.getCourseServiceHost());
                if (wfRequest.getState().equalsIgnoreCase(Constants.SEND_FOR_PC_APPROVAL) && wfRequest.getServiceName().equalsIgnoreCase(Constants.BLENDED_PROGRAM_SERVICE_NAME)) {
                    builder.append(configuration.getAdminBlendedProgramEnrolEndPoint());
                    requestBody.put(Constants.ENROLLED_DATE.toLowerCase(), new SimpleDateFormat(Constants.SIMPLE_DATE_FORMAT).format(new SimpleDateFormat(Constants.COMMON_DATE_FORMAT).parse(wfRequest.getCreatedOn().toString())));
                } else {
                    builder.append(configuration.getAdminEnrolEndPoint());
                }
                Map<String, Object> enrolResp = (Map<String, Object>) requestServiceImpl
                        .fetchResultUsingPost(builder, request, Map.class, headersValue);
                if (enrolResp != null
                        && "OK".equalsIgnoreCase((String) enrolResp.get(Constants.RESPONSE_CODE))) {
                    logger.info("User enrolment success");
                } else {
                    logger.error("user enrolment failed"
                            + ((Map<String, Object>) enrolResp.get(Constants.PARAMS)).get(Constants.ERROR_MESSAGE));
                }
            } catch (Exception e) {
                logger.error("Exception while enrol user");
            }
        }
    }

    private Map<String, Object> getCurrentBatchAttributes(String batchId, String courseId) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.BATCH_ID, batchId);
        propertyMap.put(Constants.COURSE_ID, courseId);
        List<Map<String, Object>> batchAttributesDetails = cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_COURSE_BATCH,
                propertyMap,
                Arrays.asList(Constants.BATCH_ATTRIBUTES, Constants.ENROLMENT_END_DATE, Constants.START_DATE, Constants.NAME));
        if (CollectionUtils.isNotEmpty(batchAttributesDetails)) {
            Map<String, Object> courseBatch = (Map<String, Object>) batchAttributesDetails.get(0);
            if (courseBatch.containsKey(Constants.BATCH_ATTRIBUTES)) {
                try {
                    Map<String, Object> batchAttributes = (new ObjectMapper()).readValue(
                            (String) courseBatch.get(Constants.BATCH_ATTRIBUTES),
                            new TypeReference<HashMap<String, Object>>() {
                            });

                    String currentBatchSizeString = batchAttributes != null
                            && batchAttributes.containsKey(Constants.CURRENT_BATCH_SIZE)
                                    ? (String) batchAttributes.get(Constants.CURRENT_BATCH_SIZE)
                                    : "0";
                    int currentBatchSize = Integer.parseInt(currentBatchSizeString);
                    Date enrollmentEndDate = courseBatch.containsKey(Constants.ENROLMENT_END_DATE)
                            ? (Date) courseBatch.get(Constants.ENROLMENT_END_DATE)
                            : null;
                    Date batchStartDate = courseBatch.containsKey(Constants.START_DATE)
                            ? (Date) courseBatch.get(Constants.START_DATE)
                            : null;
                    String batchName = batchAttributes != null
                            && courseBatch.containsKey(Constants.NAME)
                            ? (String) courseBatch.get(Constants.NAME)
                            : "";
                    Map<String, Object> result = new HashMap<>();
                    result.put(Constants.CURRENT_BATCH_SIZE, currentBatchSize);
                    result.put(Constants.ENROLMENT_END_DATE, enrollmentEndDate);
                    result.put(Constants.START_DATE, batchStartDate);
                    result.put(Constants.BATCH_NAME, batchName);
                    return result;
                } catch (Exception e) {
                    logger.error(String.format("Failed to retrieve course batch details. CourseId: %s, BatchId: %s",
                            courseId, batchId), e);
                }
            }
        }
        return Collections.emptyMap();
    }
    private int getTotalApprovedUserCount(WfRequest wfRequest) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.BATCH_ID, wfRequest.getApplicationId());
        List<Map<String, Object>>  list =  cassandraOperation.getRecordsByProperties(Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_ENROLMENT_BATCH_LOOKUP, propertyMap,Arrays.asList(Constants.BATCH_ID, Constants.USER_ID,Constants.ACTIVE));
           // Stream to filter and collect only non-null "endDate" maps
        return list.stream()
                .filter(item -> {
                    return item != null && item.containsKey("active") && (boolean) item.get("active") == true;
                })
                .collect(Collectors.toList()).size();
    }
    private boolean validateBatchEnrolment(Map<String, Object> courseBatchDetails, int totalApprovedUserCount,
            int totalUserEnrolCount, String bpState) {
        if (MapUtils.isEmpty(courseBatchDetails)) {
            return false;
        }
        int currentBatchSize = 0;
        if (courseBatchDetails.containsKey(Constants.CURRENT_BATCH_SIZE)) {
            currentBatchSize = (int) courseBatchDetails.get(Constants.CURRENT_BATCH_SIZE);
        }
        if (totalApprovedUserCount >= currentBatchSize) {
            return false;
        }

        if (currentBatchSize != 0 && Constants.BP_ENROLL_STATE.equals(bpState)) {
            currentBatchSize = (int) (currentBatchSize
                    + (((double) configuration.getBpBatchEnrolLimitBufferSize() / 100) * currentBatchSize));
        } else {
            totalUserEnrolCount = totalApprovedUserCount;
        }
        return totalUserEnrolCount < currentBatchSize;
    }

    @Override
    public Response blendedProgramUserSearch(String rootOrg, String org, String userId, SearchCriteria searchCriteria) {
        searchCriteria.setUserId(userId);
        Response response = workflowService.applicationsSearch(rootOrg, org, searchCriteria);
        return response;
    }

    public Response readBPWFApplication(String wfId, boolean isPc) {
        WfStatusEntity applicationStatus = wfStatusRepo.findByWfId(wfId);
        List<WfStatusEntity> applicationList = applicationStatus == null ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(applicationStatus));
        Response response = new Response();
        if (isPc) {
            // TODO - Need to enrich this response with User Profile Details ?
        }
        response.put(Constants.MESSAGE, Constants.SUCCESSFUL);
        response.put(Constants.DATA, applicationList);
        response.put(Constants.STATUS, HttpStatus.OK);
        return response;
    }

    /**
     * This method is responsible for processing the wfRequest based on the state of
     * the wfRequest
     *
     * @param wfRequest - Recieves a wfRequest with the request params.
     */
    public void processWFRequest(WfRequest wfRequest) {
        WfStatusEntity wfStatusEntity = wfStatusRepo.findByWfId(wfRequest.getWfId());
        switch (wfStatusEntity.getCurrentStatus()) {
            case Constants.APPROVED:
                updateEnrolmentDetails(wfRequest);
                break;
            case Constants.REMOVED:
                removeEnrolmentDetails(wfRequest);
                break;
            case Constants.ENROLL_IS_IN_PROGRESS:
            case Constants.ADMIN_ENROLL_IS_IN_PROGRESS:
                handleEnrollmentRequest(wfRequest);
                break;
            case Constants.ONE_STEP_MDO_APPROVAL:
            case Constants.ONE_STEP_PC_APPROVAL:
            case Constants.TWO_STEP_MDO_AND_PC_APPROVAL:
            case Constants.TWO_STEP_PC_AND_MDO_APPROVAL:
                handleApprovalRequest(wfRequest);
                break;
            default:
                logger.info("Status is Skipped by Blended Program Workflow Handler - Current Status: "
                        + wfStatusEntity.getCurrentStatus());
                break;
        }
    }


    private String validateBatchUserRequestAccess(WfRequest wfRequest,  Map<String, Object> batchDetailsMap) {
        Map<String, Object> courseBatchDetails = getCurrentBatchAttributes(wfRequest.getApplicationId(),
                wfRequest.getCourseId());
        batchDetailsMap.put(Constants.START_DATE, courseBatchDetails.get(Constants.START_DATE));
        batchDetailsMap.put(Constants.BATCH_NAME, courseBatchDetails.get(Constants.BATCH_NAME));
        boolean nonEnrolmentState = configuration.getBpBatchFullValidationExcludeStates().contains(wfRequest.getAction());
        if(nonEnrolmentState)
            return "";
        boolean batchStartDateValid = validateBatchStartDate(courseBatchDetails);
        if(!batchStartDateValid)
            return Constants.BATCH_START_DATE_ERROR;
        boolean batchSizeValidation =  validateBatchEnrolment(courseBatchDetails, getTotalApprovedUserCount(wfRequest), 0,
                Constants.BP_UPDATE_STATE);
        if(!batchSizeValidation)
            return Constants.BATCH_SIZE_ERROR;
        return "";
    }


    private boolean validateBatchStartDate(Map<String, Object> courseBatchDetails) {
        Date batchStartDate = (Date) courseBatchDetails.get(Constants.START_DATE);
        return batchStartDate.after(new Date());
    }


    public Response readStats(Map<String, Object> request) {
        Response response = new Response();
        try {
            String errMsg = validateRequest(request);
            if (StringUtils.isNotBlank(errMsg)) {
                response.put(Constants.MESSAGE, Constants.FAILED);
                response.put(Constants.ERROR_MESSAGE, errMsg);
                response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
                response.setResponseCode(HttpStatus.BAD_REQUEST);
                return response;
            }
            Map<String, Object> respCourseDetails = new HashMap<String, Object>();
            Map<String, Object> requestBody = (Map<String, Object>) request.get(Constants.REQUEST);
            List<String> courseIdList = (List<String>) requestBody.get(Constants.COURSE_ID_LIST);
            List<String> fileds = Arrays.asList(Constants.COURSE_ID, Constants.BATCH_ID, Constants.END_DATE);
            Map<String, Object> propertyMap = new HashMap<String, Object>();
            propertyMap.put(Constants.COURSE_ID, courseIdList);
            List<Map<String, Object>> courseBatchDetailsList = cassandraOperation.getRecordsByProperties(
                    Constants.KEYSPACE_SUNBIRD_COURSES, Constants.TABLE_COURSE_BATCH, propertyMap, fileds);
            for (Map<String, Object> courseBatchDetail : courseBatchDetailsList) {
                // Process batch details
                Date endDate = courseBatchDetail.containsKey(Constants.END_DATE)
                        ? (Date) courseBatchDetail.get(Constants.END_DATE)
                        : null;
                Date todayDate = new Date();
                if (todayDate.after(endDate)) {
                    continue;
                }
                String courseId = (String) courseBatchDetail.get(Constants.COURSE_ID_KEY);
                String batchId = (String) courseBatchDetail.get(Constants.BATCH_ID_KEY);
                Map<String, Object> respCourseBatch = (Map<String, Object>) respCourseDetails.get(courseId);
                Map<String, Object> respBatchDetailsMap = null;
                if (ObjectUtils.isEmpty(respCourseBatch)) {
                    respCourseBatch = new HashMap<String, Object>();
                    respCourseBatch.put(Constants.COURSE_ID, courseId);
                    respBatchDetailsMap = new HashMap<String, Object>();
                } else {
                    respBatchDetailsMap = (Map<String, Object>) respCourseBatch.get(Constants.BATCH_DETAILS_KEY);
                }

                Map<String, Object> respBatchDetail = new HashMap<String, Object>();
                // Batch is active. Get the batch enrolment details.
                List<WfStatusEntity> wfEntries = wfStatusRepo.findByServiceNameAndApplicationId(
                        Constants.BLENDED_PROGRAM_SERVICE_NAME, batchId);
                Map<String, Long> statusCount = wfEntries.stream()
                        .map(WfStatusEntity::getCurrentStatus)
                        .collect(Collectors.groupingBy(status -> status, Collectors.counting()));

                long newRequestCount = 0;
                long learnerCount = 0;
                long rejectedCount = 0;
                for (Map.Entry<String, Long> entry : statusCount.entrySet()) {
                    switch (entry.getKey()) {
                        case Constants.SEND_FOR_PC_APPROVAL:
                            newRequestCount = entry.getValue();
                            break;
                        case Constants.REJECTED:
                            rejectedCount = entry.getValue();
                            break;
                        case Constants.APPROVED:
                            learnerCount = entry.getValue();
                            break;
                        default:
                            break;
                    }
                }
                respBatchDetail.put(Constants.NEW_REQUEST_COUNT, newRequestCount);
                respBatchDetail.put(Constants.LEARNER_COUNT, learnerCount);
                respBatchDetail.put(Constants.REJECTED_COUNT, rejectedCount);

                respCourseBatch.put(Constants.TOTAL_LEARNER_COUNT,
                        ((long) respCourseBatch.getOrDefault(Constants.TOTAL_LEARNER_COUNT, 0l)) + learnerCount);
                respCourseBatch.put(Constants.TOTAL_NEW_REQUEST_COUNT,
                        ((long) respCourseBatch.getOrDefault(Constants.TOTAL_NEW_REQUEST_COUNT, 0l)) + newRequestCount);
                respCourseBatch.put(Constants.TOTAL_REJECTED_COUNT,
                        ((long) respCourseBatch.getOrDefault(Constants.TOTAL_REJECTED_COUNT, 0l)) + rejectedCount);
                respBatchDetailsMap.put(batchId, respBatchDetail);
                respCourseBatch.put(Constants.BATCH_DETAILS_KEY, respBatchDetailsMap);
                respCourseDetails.put(courseId, respCourseBatch);
            }
            response.setResponseCode(HttpStatus.OK);
            response.getResult().put(Constants.COUNT, respCourseDetails.size());
            response.getResult().put(Constants.CONTENT, respCourseDetails.values());
        } catch (Exception e) {
            String errMsg = String.format("Failed to get the stats for course. Exception: ", e.getMessage());
            response.put(Constants.ERROR_MESSAGE, errMsg);
            response.put(Constants.STATUS, HttpStatus.INTERNAL_SERVER_ERROR);
            response.setResponseCode(HttpStatus.INTERNAL_SERVER_ERROR);
            logger.error(errMsg, e);
        }
        return response;
    }

    private String validateRequest(Map<String, Object> request) {
        String errMsg = "";
        Map<String, Object> requestBody = (Map<String, Object>) request.get(Constants.REQUEST);
        if (ObjectUtils.isEmpty(requestBody)) {
            errMsg = "Invalid Request";
            return errMsg;
        }

        if (ObjectUtils.isEmpty(((List<String>) requestBody.get(Constants.COURSE_ID_LIST)))) {
            errMsg = "Invalid Request. CourseIdList is empty.";
        }
        return errMsg;
    }

    private int getTotalUserEnrolCountForBatch(String applicationId) {
        List<WfStatusEntity> wfEntries = wfStatusRepo
                .findByApplicationId(applicationId);
        wfEntries = wfEntries.stream().filter(wfEntry -> !configuration.getBpBatchFullValidationExcludeStates()
                        .contains(wfEntry.getCurrentStatus()))
                .collect(Collectors.toList());
        return wfEntries.size();
    }


    /**
     * Service method to handle the user enrolled by the admin.
     *
     * @param rootOrg   - Root Organization Name ex: "igot"
     * @param org       - Organization name ex: "dopt"
     * @param wfRequest - WorkFlow request which needs to be processed.
     * @return - Return the response of success/failure after processing the request.
     */
    @Override
    public Response adminEnrolBPWorkFlow(String rootOrg, String org, WfRequest wfRequest) {
        Map<String, Object> courseBatchDetails = getCurrentBatchAttributes(wfRequest.getApplicationId(),
                wfRequest.getCourseId());
        String serviceName = contentReadService.getServiceNameDetails(wfRequest.getCourseId());
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = Constants.BLENDED_PROGRAM_SERVICE_NAME;
        }
        int totalUserEnrolCount = getTotalUserEnrolCountForBatch(wfRequest.getApplicationId());
        int totalApprovedUserCount = getTotalApprovedUserCount(wfRequest);
        boolean enrolAccess = validateBatchEnrolment(courseBatchDetails, totalApprovedUserCount, totalUserEnrolCount,
                Constants.BP_ENROLL_STATE);
        if (!enrolAccess) {
            Response response = new Response();
            response.put(Constants.ERROR_MESSAGE, configuration.getBatchFullMesg());
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        Response response;
        if (!scheduleConflictCheck(wfRequest)) {
            List<WfStatusEntity> enrollmentStatus = wfStatusRepo.findByServiceNameAndUserIdAndApplicationId(wfRequest.getServiceName(), wfRequest.getUserId(), wfRequest.getApplicationId());

            if (!enrollmentStatus.isEmpty()) {
                response = new Response();
                response.put(Constants.MESSAGE, "Not allowed to enroll the user to the Blended Program");
                response.put(Constants.STATUS, HttpStatus.OK);
            } else {
                wfRequest.setAction(Constants.INITIATE);
                wfRequest.setNominatedByMdo(true);
                wfRequest.setBatchName((String) courseBatchDetails.get(Constants.BATCH_NAME));
                wfRequest.setBatchStartDate((Date) courseBatchDetails.get(Constants.START_DATE));
                response = saveAdminEnrollUserIntoWfStatus(rootOrg, org, wfRequest);
               // producer.push(configuration.getWorkFlowNotificationTopic(), wfRequest);
                producer.push(configuration.getWorkflowApplicationTopic(), wfRequest);
            }
        } else {
            response = new Response();
            response.put(Constants.MESSAGE, "Not allowed to enroll the user to the Blended Program since there is a schedule conflict");
            response.put(Constants.STATUS, HttpStatus.NOT_ACCEPTABLE);
        }
        return response;
    }

    /**
     * Save Method to save the admin enrolled data into the wf_status table.
     *
     * @param rootOrg   - Root Organization Name ex: "igot"
     * @param org       - Organization name ex: "dopt"
     * @param wfRequest - WorkFlow request which needs to be processed.
     * @return - Return the response of success/failure after processing the request.
     */
    private Response saveAdminEnrollUserIntoWfStatus(String rootOrg, String org, WfRequest wfRequest) {
        validateWfRequest(wfRequest);
        WfStatusEntity applicationStatus = new WfStatusEntity();
        String wfId = UUID.randomUUID().toString();
        applicationStatus.setWfId(wfId);
        applicationStatus.setApplicationId(wfRequest.getApplicationId());
        applicationStatus.setUserId(wfRequest.getUserId());
        applicationStatus.setInWorkflow(true);
        applicationStatus.setServiceName(wfRequest.getServiceName());
        applicationStatus.setActorUUID(wfRequest.getActorUserId());
        applicationStatus.setCreatedOn(new Date());
        applicationStatus.setCurrentStatus(Constants.ADMIN_ENROLL_IS_IN_PROGRESS);
        applicationStatus.setLastUpdatedOn(new Date());
        applicationStatus.setOrg(org);
        applicationStatus.setRootOrg(rootOrg);
        applicationStatus.setAdditionalProperties("isNominatedByMdo:"+wfRequest.isNominatedByMdo());
        try {
            applicationStatus.setUpdateFieldValues(mapper.writeValueAsString(wfRequest.getUpdateFieldValues()));
        } catch (JsonProcessingException e) {
            logger.error(String.valueOf(e));
        }
        applicationStatus.setDeptName(wfRequest.getDeptName());
        applicationStatus.setComment(wfRequest.getComment());
        wfRequest.setWfId(wfId);
        wfStatusRepo.save(applicationStatus);

        Response response = new Response();
        HashMap<String, Object> data = new HashMap<>();
        data.put(Constants.STATUS, Constants.ADMIN_ENROLL_IS_IN_PROGRESS);
        data.put(Constants.WF_IDS_CONSTANT, wfId);
        response.put(Constants.MESSAGE, Constants.STATUS_CHANGE_MESSAGE + Constants.ADMIN_ENROLL_IS_IN_PROGRESS);
        response.put(Constants.DATA, data);
        
               response.put(Constants.STATUS, HttpStatus.OK);
        return response;
    }

    @Override
    public Response removeBPWorkFlow(String rootOrg, String org, WfRequest wfRequest,String userId,String role) {
        Response response = new Response();
        Map<String, Object> courseBatchDetails = getCurrentBatchAttributes(wfRequest.getApplicationId(),
                wfRequest.getCourseId());
        int totalApprovedUserCount = getTotalApprovedUserCount(wfRequest);
        boolean enrolAccess = validateBatchEnrolment(courseBatchDetails, totalApprovedUserCount, 0,
                Constants.BP_UPDATE_STATE);
        List<WfStatusEntity> approvedLearners = wfStatusRepo.findByApplicationIdAndUserIdAndCurrentStatus(wfRequest.getApplicationId(), wfRequest.getUserId(), wfRequest.getState());
        if (enrolAccess && approvedLearners.size() > 1) {
            response.put(Constants.ERROR_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        } else if (approvedLearners.size() == 1)
            wfRequest.setWfId(approvedLearners.get(0).getWfId());
        wfRequest.setBatchName((String) courseBatchDetails.get(Constants.BATCH_NAME));
        wfRequest.setBatchStartDate((Date) courseBatchDetails.get(Constants.START_DATE));
        response = workflowService.workflowTransition(rootOrg, org, wfRequest, userId,role);
        response.put(Constants.STATUS, HttpStatus.OK);

        return response;
    }

    /**
     * @param wfRequest - Validate the fields received in the wfRequest.
     */
    private void validateWfRequest(WfRequest wfRequest) {

        if (StringUtils.isEmpty(wfRequest.getState())) {
            throw new InvalidDataInputException(Constants.STATE_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getApplicationId())) {
            throw new InvalidDataInputException(Constants.APPLICATION_ID_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getActorUserId())) {
            throw new InvalidDataInputException(Constants.ACTOR_UUID_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getUserId())) {
            throw new InvalidDataInputException(Constants.USER_UUID_VALIDATION_ERROR);
        }


        if (CollectionUtils.isEmpty(wfRequest.getUpdateFieldValues())) {
            throw new InvalidDataInputException(Constants.FIELD_VALUE_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getServiceName())) {
            throw new InvalidDataInputException(Constants.WORKFLOW_SERVICENAME_VALIDATION_ERROR);
        }
    }


    /**
     * Main method is responsible for checking the schedule conflicts wrt enrollment of user into blended program.
     *
     * @param wfRequest - WorkFlow request which needs to be processed.
     * @return - return the response of success/failure after processing the request.
     */
    public boolean scheduleConflictCheck(WfRequest wfRequest) {
        final Date[] wfBatchStartDate = new Date[1];
        final Date[] wfBatchEndDate = new Date[1];
        List<Map<String, Object>> userEnrollmentBatchDetailsList = getUserEnrolmentDetails(wfRequest);
        List<Map<String, Object>> courseBatchWfRequestList = getCourseBatchDetailWfRequest(wfRequest);
        List<Map<String, Object>> enrolledCourseBatchList = getCourseBatchDetails(userEnrollmentBatchDetailsList);
        courseBatchWfRequestList.stream().flatMap(courseBatchWfRequest -> courseBatchWfRequest.entrySet().stream()).forEach(entry -> {
            if (entry.getKey().equals(Constants.START_DATE)) {
                Date startDate = (Date) entry.getValue();
                if (startDate != null) {
                    wfBatchStartDate[0] = startDate;
                }
            }
            if (entry.getKey().equals(Constants.END_DATE)) {
                Date endDate = (Date) entry.getValue();
                if (endDate != null) {
                    wfBatchEndDate[0] = endDate;
                }
            }
        });
        return enrollmentDateValidations(enrolledCourseBatchList, wfBatchStartDate, wfBatchEndDate);
    }

    /**
     * This method is responsible  for checking the date conflicts of the blended program
     * received from wfRequest with the blended programs the user is already enrolled into.
     *
     * @param enrolledCourseBatchList - contains details of the enrolled courses for the user.
     * @param startDate               - startDate for the course received from the wfRequest.
     * @param endDate-                endDate for the course received from the wfRequest.
     * @return - return a boolean value 'true' is there is conflict of the dates.
     */
    public boolean enrollmentDateValidations(List<Map<String, Object>> enrolledCourseBatchList, Date[] startDate, Date[] endDate) {
        final boolean[] startDateFlag = {false};
        final boolean[] endDateFlag = {false};
        enrolledCourseBatchList.forEach(enrolledCourseBatch -> enrolledCourseBatch.forEach((key, value) -> {
            Date startDateValue = (Date) enrolledCourseBatch.get(Constants.START_DATE);
            Date endDateValue = (Date) enrolledCourseBatch.get(Constants.END_DATE);
            if (startDateValue != null && isWithinRange(startDateValue, startDate[0], endDate[0])) {
                logger.info("The user is not allowed to enroll for the course since there is a conflict" + startDateValue + startDate[0] + endDate[0]);
                startDateFlag[0] = true;
            } else {
                startDateFlag[0] = false;
            }

            if (endDateValue != null && isWithinRange(endDateValue, startDate[0], endDate[0])) {
                logger.info("The user is not allowed to enroll for the course since there is a conflict" + endDateValue + startDate[0] + endDate[0]);
                endDateFlag[0] = true;
            } else {
                endDateFlag[0] = false;
            }
        }));
        return startDateFlag[0] || endDateFlag[0];
    }

    /**
     * This method returns the list of courses the user is enrolled into.
     *
     * @param wfRequest - WorkFlow request which contains the parameters.
     * @return - return a list of the user_enrolment details based on the userid passed.
     */
    public List<Map<String, Object>> getUserEnrolmentDetails(WfRequest wfRequest) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.USER_ID, wfRequest.getUserId());
        List<Map<String, Object>> allEnrollmentDetails = cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.USER_ENROLMENTS,
                propertyMap,
                Arrays.asList(Constants.BATCH_ID, Constants.USER_ID, Constants.COURSE_ID, Constants.ACTIVE)
        );
        return allEnrollmentDetails.stream()
                .filter(e -> e != null && e.get(Constants.ACTIVE) != null && (boolean) e.get(Constants.ACTIVE))
                .collect(Collectors.toList());
    }

    /**
     * This method returns the course_batch details for the blended program received from the wfRequest.
     *
     * @param wfRequest -  WorkFlow request which contains the parameters.
     * @return - return a list of the course_batch details based on the courseId and batchId passed.
     */
    public List<Map<String, Object>> getCourseBatchDetailWfRequest(WfRequest wfRequest) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.COURSE_ID, wfRequest.getCourseId());
        propertyMap.put(Constants.BATCH_ID, wfRequest.getApplicationId());
        return cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_COURSE_BATCH,
                propertyMap,
                Arrays.asList(Constants.BATCH_ID, Constants.COURSE_ID, Constants.START_DATE, Constants.END_DATE)
        );
    }

    /**
     * This method returns the course_batch details for the blended program based on the user_enrolment details.
     *
     * @param userEnrollmentBatchDetailsList - To get the course batch details we need the user_enrolment table details specifically - courseId and batchId
     * @return - return a list of the course_batch details based on the courseId and batchId passed.
     */
    public List<Map<String, Object>> getCourseBatchDetails(List<Map<String, Object>> userEnrollmentBatchDetailsList) {
        List<String> coursesList = new ArrayList<>();
        List<String> batchidsList = new ArrayList<>();
        userEnrollmentBatchDetailsList.forEach(userEnrollmentBatchDetail ->
                userEnrollmentBatchDetail.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(Constants.COURSE_ID) ||
                                entry.getKey().equalsIgnoreCase(Constants.BATCH_ID))
                        .forEach(entry -> {
                            if (entry.getKey().equalsIgnoreCase(Constants.COURSE_ID)) {
                                coursesList.add(entry.getValue().toString());
                            }
                            if (entry.getKey().equalsIgnoreCase(Constants.BATCH_ID)) {
                                batchidsList.add(entry.getValue().toString());
                            }
                        })
        );

        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.COURSE_ID, coursesList);
        propertyMap.put(Constants.BATCH_ID, batchidsList);
        //propertyMap.put(Constants.ENROLLMENT_TYPE, Constants.INVITE_ONLY);
        List<Map<String, Object>> list= cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_COURSE_BATCH,
                propertyMap,
                Arrays.asList(Constants.BATCH_ID, Constants.COURSE_ID, Constants.START_DATE, Constants.END_DATE)
        );

        // Stream to filter and collect only non-null "endDate" maps
        return list.stream()
                .filter(item -> {
                    return item != null && item.containsKey("end_date") && item.get("end_date") != null;
                })
                .collect(Collectors.toList());
    }

    /**
     * This method is responsible to check the date in a specific range.
     *
     * @param date        - The needs to be checked whether it is in the range.
     * @param startDate   -The startDate wrt to the Blended program to be enrolled in.
     * @param endDate-The endDate wrt to the Blended program to be enrolled in.
     * @return - Boolean value if the date is in the range of the Blended program enrollment.
     */
    public static boolean isWithinRange(Date date, Date startDate, Date endDate) {
        return date.compareTo(startDate) >= 0 && date.compareTo(endDate) <= 0;
    }

    /**
     * This method is responsible for removing a user enrollment details
     *
     * @param wfRequest - Receives a wfRequest with the request params.
     */
    @Override
    public void removeEnrolmentDetails(WfRequest wfRequest) {
        Map<String, Object> courseBatchDetails = getCurrentBatchAttributes(wfRequest.getApplicationId(),
                wfRequest.getCourseId());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put(Constants.USER_ID, wfRequest.getUserId());
            requestBody.put(Constants.BATCH_ID, wfRequest.getApplicationId());
            requestBody.put(Constants.COURSE_ID, wfRequest.getCourseId());
            Map<String, Object> request = new HashMap<>();
            request.put(Constants.REQUEST,requestBody);
            HashMap<String, String> headersValue = new HashMap<>();
            headersValue.put("Content-Type", "application/json");
            try {
                StringBuilder builder = new StringBuilder(configuration.getCourseServiceHost());
                builder.append(configuration.getAdminUnEnrolEndPoint());
                Map<String, Object> enrolResp = (Map<String, Object>) requestServiceImpl
                        .fetchResultUsingPost(builder, request, Map.class, headersValue);
                if (enrolResp != null
                        && "OK".equalsIgnoreCase((String) enrolResp.get(Constants.RESPONSE_CODE))) {
                    logger.info("User un-enrollment success");
                } else {
                    logger.error("user un-enrollment failed" + ((Map<String, Object>) enrolResp.get(Constants.PARAMS)).get(Constants.ERROR_MESSAGE));
                }
            } catch (Exception e) {
                logger.error("Exception while un-enrol user");
            }
    }

    /**
     * This will handle the enrolment once the event is triggered from the kafka topic
     *
     * @param wfRequest - WorkFlow request which needs to be processed.
     */
    private void handleEnrollmentRequest(WfRequest wfRequest) {
        String serviceName = contentReadService.getServiceNameDetails(wfRequest.getCourseId());
        if (serviceName == null || serviceName.isEmpty()) {
            serviceName = wfRequest.getServiceName();
        }
        try {
            WfStatusEntity applicationStatus = wfStatusRepo.findByWfId(wfRequest.getWfId());
            WorkFlowModel workFlowModel = getWorkFlowConfig(serviceName);
            WfStatus wfStatus = getWfStatus(applicationStatus.getCurrentStatus(), workFlowModel);
            WfAction wfAction = getWfAction(wfRequest.getAction(), wfStatus);

            String nextState = wfAction.getNextState();
            WfStatus wfStatusCheckForNextState = getWfStatus(nextState, workFlowModel);

            applicationStatus.setLastUpdatedOn(new Date());
            applicationStatus.setCurrentStatus(nextState);
            applicationStatus.setActorUUID(wfRequest.getActorUserId());
            applicationStatus.setUpdateFieldValues(mapper.writeValueAsString(wfRequest.getUpdateFieldValues()));
            applicationStatus.setInWorkflow(!wfStatusCheckForNextState.getIsLastState());
            applicationStatus.setDeptName(wfRequest.getDeptName());
            applicationStatus.setComment(wfRequest.getComment());
            applicationStatus.setServiceName(serviceName);
            wfStatusRepo.save(applicationStatus);
            producer.push(configuration.getWorkflowApplicationTopic(), wfRequest);
            producer.push(configuration.getWorkFlowNotificationTopic(), wfRequest);
        } catch (IOException e) {
            throw new ApplicationException(Constants.WORKFLOW_PARSING_ERROR_MESSAGE, e);
        }
    }



    /**
     * @param serviceName -the serviceName based on which the enrollment configuration json is fetched from the system_settings.
     * @return - the workflow-model object with the json.
     */
    public WorkFlowModel getWorkFlowConfig(String serviceName) {
        try {
            Map<String, Object> wfConfig;
            StringBuilder uri = new StringBuilder();
            switch (serviceName) {
                case Constants.ONE_STEP_PC_APPROVAL:
                    uri.append(configuration.getLmsServiceHost()).append(configuration.getMultilevelBPEnrolEndPoint()).append(Constants.ONE_STEP_PC_APPROVAL);
                    break;
                case Constants.ONE_STEP_MDO_APPROVAL:
                    uri.append(configuration.getLmsServiceHost()).append(configuration.getMultilevelBPEnrolEndPoint()).append(Constants.ONE_STEP_MDO_APPROVAL);
                    break;
                case Constants.TWO_STEP_MDO_AND_PC_APPROVAL:
                    uri.append(configuration.getLmsServiceHost()).append(configuration.getMultilevelBPEnrolEndPoint()).append(Constants.TWO_STEP_MDO_AND_PC_APPROVAL);
                    break;
                case Constants.TWO_STEP_PC_AND_MDO_APPROVAL:
                    uri.append(configuration.getLmsServiceHost()).append(configuration.getMultilevelBPEnrolEndPoint()).append(Constants.TWO_STEP_PC_AND_MDO_APPROVAL);
                    break;
                default:
                    break;
            }
            wfConfig = (Map<String, Object>) requestServiceImpl.fetchResultUsingGet(uri);
            Map<String, Object> result = (Map<String, Object>) wfConfig.get(Constants.RESULT);
            Map<String, Object> response = (Map<String, Object>) result.get(Constants.RESPONSE);
            Map<String, Object> wfStates = mapper.readValue((String) response.get(Constants.VALUE), Map.class);
            return mapper.convertValue(wfStates, new TypeReference<WorkFlowModel>() {
            });
        } catch (Exception e) {
            logger.error("Exception occurred while getting work flow config details!");
            throw new ApplicationException(Constants.WORKFLOW_PARSING_ERROR_MESSAGE, e);
        }
    }

    /**
     * Save Method to save the enrolled user data into the wf_status table.
     *
     * @param rootOrg   - Root Organization Name ex: "igot"
     * @param org       - Organization name ex: "dopt"
     * @param wfRequest - WorkFlow request which needs to be processed.
     * @return - Return the response of success/failure after processing the request.
     */
    private Response saveEnrollUserIntoWfStatus(String rootOrg, String org, WfRequest wfRequest) {
        WfStatusEntity applicationStatus = new WfStatusEntity();
        String wfId = UUID.randomUUID().toString();
        applicationStatus.setWfId(wfId);
        applicationStatus.setApplicationId(wfRequest.getApplicationId());
        applicationStatus.setUserId(wfRequest.getUserId());
        applicationStatus.setInWorkflow(true);
        applicationStatus.setActorUUID(wfRequest.getActorUserId());
        applicationStatus.setCreatedOn(new Date());
        applicationStatus.setCurrentStatus(Constants.ENROLL_IS_IN_PROGRESS);
        applicationStatus.setLastUpdatedOn(new Date());
        applicationStatus.setOrg(org);
        applicationStatus.setRootOrg(rootOrg);
        try {
            applicationStatus.setUpdateFieldValues(mapper.writeValueAsString(wfRequest.getUpdateFieldValues()));
        } catch (JsonProcessingException e) {
            logger.error(String.valueOf(e));
        }
        applicationStatus.setDeptName(wfRequest.getDeptName());
        applicationStatus.setComment(wfRequest.getComment());
        wfRequest.setWfId(wfId);
        wfStatusRepo.save(applicationStatus);
        Response response = new Response();
        HashMap<String, Object> data = new HashMap<>();
        data.put(Constants.STATUS, Constants.ENROLL_IS_IN_PROGRESS);
        data.put(Constants.WF_IDS_CONSTANT, wfId);
        response.put(Constants.MESSAGE, Constants.STATUS_CHANGE_MESSAGE + Constants.ENROLL_IS_IN_PROGRESS);
        response.put(Constants.DATA, data);
        response.put(Constants.STATUS, HttpStatus.OK);
        return response;
    }

    /**
     * Get the workflow State based on given state
     *
     * @param state         - Current state fetched from the wf_status table.
     * @param workFlowModel - Workflow model object
     * @return Workflow State
     */
    private WfStatus getWfStatus(String state, WorkFlowModel workFlowModel) {
        WfStatus wfStatus = null;
        for (WfStatus status : workFlowModel.getWfstates()) {
            if (status.getState().equals(state)) {
                wfStatus = status;
            }
        }
        if (ObjectUtils.isEmpty(wfStatus)) {
            throw new BadRequestException(Constants.WORKFLOW_STATE_CHECK_ERROR);
        }
        return wfStatus;
    }

    /**
     * Get Workflow Action based on given action
     *
     * @param action   - Action that needs to be performed. -INITIATE/APPROVE .
     * @param wfStatus -wfStatus object
     * @return Work flow Action
     */
    private WfAction getWfAction(String action, WfStatus wfStatus) {
        WfAction wfAction = null;
        if (ObjectUtils.isEmpty(wfStatus.getActions())) {
            throw new BadRequestException(Constants.WORKFLOW_ACTION_ERROR);
        }
        for (WfAction filterAction : wfStatus.getActions()) {
            if (action.equals(filterAction.getAction())) {
                wfAction = filterAction;
            }
        }
        if (ObjectUtils.isEmpty(wfAction)) {
            throw new BadRequestException(Constants.WORKFLOW_ACTION_ERROR);
        }
        return wfAction;
    }

    private void validateWfRequestMultilevelEnrol(WfRequest wfRequest) {

        if (StringUtils.isEmpty(wfRequest.getState())) {
            throw new InvalidDataInputException(Constants.STATE_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getApplicationId())) {
            throw new InvalidDataInputException(Constants.APPLICATION_ID_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getActorUserId())) {
            throw new InvalidDataInputException(Constants.ACTOR_UUID_VALIDATION_ERROR);
        }

        if (StringUtils.isEmpty(wfRequest.getUserId())) {
            throw new InvalidDataInputException(Constants.USER_UUID_VALIDATION_ERROR);
        }


        if (CollectionUtils.isEmpty(wfRequest.getUpdateFieldValues())) {
            throw new InvalidDataInputException(Constants.FIELD_VALUE_VALIDATION_ERROR);
        }
    }


    private void handleApprovalRequest(WfRequest wfRequest) {
        String nextState = null;
        try {
            WfStatusEntity applicationStatus = wfStatusRepo.findByWfId(wfRequest.getWfId());
            WorkFlowModel workFlowModel = getWorkFlowConfig(wfRequest.getServiceName());
            WfStatus wfStatus = getWfStatus(applicationStatus.getCurrentStatus(), workFlowModel);
            WfAction wfAction = getWfAction(wfRequest.getAction(), wfStatus);

            nextState = wfAction.getNextState();
            WfStatus wfStatusCheckForNextState = getWfStatus(nextState, workFlowModel);

            applicationStatus.setLastUpdatedOn(new Date());
            applicationStatus.setCurrentStatus(nextState);
            applicationStatus.setActorUUID(wfRequest.getActorUserId());
            applicationStatus.setUpdateFieldValues(mapper.writeValueAsString(wfRequest.getUpdateFieldValues()));
            applicationStatus.setInWorkflow(!wfStatusCheckForNextState.getIsLastState());
            applicationStatus.setDeptName(wfRequest.getDeptName());
            applicationStatus.setComment(wfRequest.getComment());
            applicationStatus.setServiceName(wfRequest.getServiceName());
            wfStatusRepo.save(applicationStatus);
            producer.push(configuration.getWorkflowApplicationTopic(), wfRequest);
            producer.push(configuration.getWorkFlowNotificationTopic(), wfRequest);
        } catch (IOException e) {
            throw new ApplicationException(Constants.WORKFLOW_PARSING_ERROR_MESSAGE, e);
        }
    }

}
