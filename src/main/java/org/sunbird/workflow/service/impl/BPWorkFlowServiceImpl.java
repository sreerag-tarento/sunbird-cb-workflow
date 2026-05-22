package org.sunbird.workflow.service.impl;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;
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
import org.sunbird.workflow.utils.UserUtil;

import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class BPWorkFlowServiceImpl implements BPWorkFlowService {

    private Logger logger = LoggerFactory.getLogger(BPWorkFlowServiceImpl.class);

    @Autowired
    @Qualifier("workflowServiceImpl")
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

    @Autowired
    private UserUtil userUtils;

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
        wfRequest.setBatchStartDate((Date)courseBatchDetails.get(Constants.START_DATE));
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
        wfRequest.setBatchStartDate((Date)batchDetailsMap.get(Constants.START_DATE));
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
        if (!Constants.REMOVE.equalsIgnoreCase(wfRequest.getAction()) && scheduleConflictCheck(wfRequest)) {
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
                    requestBody.put(Constants.ENROLLED_DATE.toLowerCase(), wfRequest.getCreatedOn());
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
                    if (wfRequest.getState().equalsIgnoreCase(Constants.SEND_FOR_PC_APPROVAL) && wfRequest.getServiceName().equalsIgnoreCase(Constants.BLENDED_PROGRAM_SERVICE_NAME)) {
                        reverseApprovedRequest(wfRequest);
                    }
                }

            } catch (Exception e) {
                logger.error("Exception while enrol user");
                if (wfRequest.getState().equalsIgnoreCase(Constants.SEND_FOR_PC_APPROVAL) && wfRequest.getServiceName().equalsIgnoreCase(Constants.BLENDED_PROGRAM_SERVICE_NAME)) {
                    reverseApprovedRequest(wfRequest);
                }
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
                            ? Date.from((Instant) courseBatch.get(Constants.ENROLMENT_END_DATE))
                            : null;
                    Date batchStartDate = courseBatch.containsKey(Constants.START_DATE)
                            ? Date.from((Instant) courseBatch.get(Constants.START_DATE))
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
        boolean batchStartDateValid = validateBatchStartDate(courseBatchDetails, wfRequest.getServiceName());
        if(!batchStartDateValid)
            return Constants.BATCH_START_DATE_ERROR;
        boolean batchSizeValidation =  validateBatchEnrolment(courseBatchDetails, getTotalApprovedUserCount(wfRequest), 0,
                Constants.BP_UPDATE_STATE);
        if(!batchSizeValidation)
            return Constants.BATCH_SIZE_ERROR;
        return "";
    }


    private boolean validateBatchStartDate(Map<String, Object> courseBatchDetails, String serviceName) {
        Date batchStartDate = ((Date) courseBatchDetails.get(Constants.START_DATE));

        if (Constants.BLENDED_PROGRAM_SERVICE_NAME.equalsIgnoreCase(serviceName)) {
            LocalDate batchStartLocalDate = batchStartDate.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            LocalDate currentLocalDate = LocalDate.now();
            logger.info("Batch Start LocalDate: {}, Current LocalDate: {}", batchStartLocalDate, currentLocalDate);
            return !batchStartLocalDate.isBefore(currentLocalDate);
        }
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
                        ? Date.from((Instant) courseBatchDetail.get(Constants.END_DATE))
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
        wfRequest.setBatchStartDate(Date.from((Instant) courseBatchDetails.get(Constants.START_DATE)));
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
                Date startDate = Date.from((Instant) entry.getValue());
                if (startDate != null) {
                    wfBatchStartDate[0] = startDate;
                }
            }
            if (entry.getKey().equals(Constants.END_DATE)) {
                Date endDate = Date.from((Instant) entry.getValue());
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
        boolean hasConflict = enrolledCourseBatchList.stream().anyMatch(enrolledCourseBatch -> {
            Date startDateValue = Date.from((Instant) enrolledCourseBatch.get(Constants.START_DATE));
            Date endDateValue = Date.from((Instant) enrolledCourseBatch.get(Constants.END_DATE));

            if (startDateValue != null && isWithinRange(startDateValue, startDate[0], endDate[0])) {
                logger.info("Conflict detected: START_DATE = {}, range = {} to {}", startDateValue, startDate[0], endDate[0]);
                return true;
            }

            if (endDateValue != null && isWithinRange(endDateValue, startDate[0], endDate[0])) {
                logger.info("Conflict detected: END_DATE = {}, range = {} to {}", endDateValue, startDate[0], endDate[0]);
                return true;
            }

            return false;
        });

        return hasConflict;
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
                Constants.USER_ENROLMENTS_V2,
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

        if (CollectionUtils.isNotEmpty(coursesList) || CollectionUtils.isNotEmpty(batchidsList)) {
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
        } else {
            return new ArrayList<>();
        }

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


    @Override
    public ResponseEntity<ByteArrayResource> generateUserApprovalCsv(SearchCriteria criteria) {
        Response response = workflowService.applicationsSearch(null, null, criteria,
                Constants.BLENDED_PROGRAM_SEARCH_ENABLED);

        List<LinkedHashMap<String, String>> userApprovalRecords = new ArrayList<>();
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) response.getResult().get(Constants.DATA);
        if (ObjectUtils.isEmpty(dataList)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ByteArrayResource("No data found".getBytes()));
        }
        for (Map<String, Object> item : dataList) {
            Map<String, Object> userInfo = (Map<String, Object>) item.get(Constants.USER_INFO);
            List<WfStatusEntity> wfInfos = (List<WfStatusEntity>) item.get(Constants.WF_INFO);
            String email = "";
            String firstName = "";
            if (Objects.nonNull(userInfo)) {
                email = (String) userInfo.get(Constants.EMAIL);
                firstName = (String) userInfo.get(Constants.FIRST_NAME);
            }
            if (CollectionUtils.isEmpty(wfInfos)) {
                userApprovalRecords.add(buildRow(email, firstName, "", ""));
            } else {
                for (WfStatusEntity wfInfo : wfInfos) {
                    String wfId = wfInfo.getWfId();
                    String userId = wfInfo.getUserId();
                    userApprovalRecords.add(buildRow(email, firstName, wfId, userId));
                }
            }
        }
        if (userApprovalRecords.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ByteArrayResource("No valid user approval data found".getBytes()));
        }
        File logFile = null;
        try {
            logFile = writeApprovalDataToCsv(userApprovalRecords, "BP_user_approval_data");
            byte[] fileContent = Files.readAllBytes(logFile.toPath());
            ByteArrayResource resource = new ByteArrayResource(fileContent);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + logFile.getName())
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .contentLength(fileContent.length)
                    .body(resource);

        } catch (IOException e) {
            logger.error("Error writing approval CSV file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ByteArrayResource("Error generating CSV file".getBytes()));
        } finally {
            deleteTempFile(logFile);
        }
    }

    private LinkedHashMap<String, String> buildRow(String email, String firstName, String wfId, String userId) {
        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        row.put(Constants.EMAIL, email);
        row.put(Constants.USER_NAME, firstName);
        row.put(Constants.WF_ID_CONSTANT, wfId);
        row.put(Constants.USER_ID, userId);
        row.put(Constants.ACTION_APPROVE_REJECT, "");
        return row;
    }

    public File writeApprovalDataToCsv(List<LinkedHashMap<String, String>> logs, String originalFileName) throws IOException {
        logger.info("Logs written to file: {}", originalFileName);
        String csvFileName = originalFileName + "_log.csv";
        String tempDir = System.getProperty("java.io.tmpdir");
        String csvFilePath = tempDir + File.separator + csvFileName;
        File logFile = new File(csvFilePath);
        if (!logFile.exists()) {
            logFile.getParentFile().mkdirs();
            logFile.createNewFile();
        }
        try (FileWriter writer = new FileWriter(csvFilePath)) {
            if (!logs.isEmpty()) {
                LinkedHashMap<String, String> firstLog = logs.get(0);
                StringBuilder header = new StringBuilder();
                for (String key : firstLog.keySet()) {
                    header.append(escapeSpecialCharacters(key)).append(",");
                }
                writer.write(header.toString());
                writer.write(System.lineSeparator());
                for (LinkedHashMap<String, String> logEntry : logs) {
                    StringBuilder row = new StringBuilder();
                    for (String key : firstLog.keySet()) {
                        row.append(escapeSpecialCharacters(logEntry.getOrDefault(key, ""))).append(",");
                    }
                    writer.write(row.toString());
                    writer.write(System.lineSeparator());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return logFile;
    }

    private String escapeSpecialCharacters(String value) {
        if (StringUtils.isEmpty(value)) {
            logger.error("Value is null");
            return "";
        }
        String escapedValue = value;
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            escapedValue = "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return escapedValue;
    }

    /**
     * This method is responsible for loading the approval data from the CSV file.
     *
     * @param file      - The uploaded CSV file
     * @param contentId - Content ID
     * @return - ResponseEntity with the result of the operation
     */
    @Override
    public ResponseEntity<?> loadApprovalDataFromCsv(MultipartFile file, String contentId) throws IOException {
        logger.info("BPWorkFlowServiceImpl::loadApprovalDataFromCsv");
        SBApiResponse response = SBApiResponse.createDefaultResponse(Constants.API_WORKFLOW_LOAD_CSV_BULK_APPROVAL);
        String fileName = file.getOriginalFilename();
        if (!isValidFileFormat(fileName)) {
            logger.error("Invalid file format for file: {}", fileName);
            response.getParams().setStatus(Constants.FAILED);
            response.getParams().setErrmsg("Invalid file format. Only  CSV (.csv) files are supported.");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        List<String> validationErrors = new ArrayList<>();
        List<Map<String, String>> validRows = validateAndExtractApprovalData(file, validationErrors);

        if (!validationErrors.isEmpty()) {
            response.getParams().setStatus(Constants.FAILED);
            response.getResult().put("validationErrors", validationErrors);
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
        List<String[]> updatedRows = new ArrayList<>();
        for (Map<String, String> row : validRows) {
            String wfId = row.get(Constants.WF_ID_CONSTANT);
            WfStatusEntity wfStatus = wfStatusRepo.findByWfId(wfId);

            if (wfStatus == null) {
                logger.error("Workflow ID not found: {}", wfId);
                response.getResult().computeIfAbsent("missingWorkflows", k -> new ArrayList<String>());
                ((List<String>) response.getResult().get("missingWorkflows")).add(wfId);
                continue;
            }
            processRow(row, wfStatus, contentId, updatedRows, response);
        }
        return prepareCsvResponse(file, updatedRows);
    }

    private boolean isValidFileFormat(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lowerCaseFileName = fileName.toLowerCase();
        return lowerCaseFileName.endsWith(".csv");
    }

    private void deleteTempFile(File logFile) {
        if (logFile != null && logFile.exists()) {
            try {
                Files.delete(logFile.toPath());
            } catch (IOException ex) {
                logger.warn("Failed to delete temp file: {}", logFile.getAbsolutePath(), ex);
            }
        }
    }

    private List<Map<String, String>> validateAndExtractApprovalData(MultipartFile file, List<String> errors) {
        List<Map<String, String>> validRows = new ArrayList<>();
        final List<String> expectedHeaders = List.of(Constants.EMAIL, Constants.USER_NAME, Constants.WF_ID_CONSTANT, Constants.USER_ID, Constants.ACTION_APPROVE_REJECT);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean isFirstLine = true;
            int rowNumber = 1;
            List<String> actualHeaders = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                if (isFirstLine) {
                    actualHeaders = parseAndValidateHeaders(line, expectedHeaders, errors);
                    if (!errors.isEmpty()) return validRows;
                    isFirstLine = false;
                    continue;
                }
                rowNumber++;
                Map<String, String> row = processDataRow(line, actualHeaders, rowNumber, errors);
                if (row != null) {
                    validRows.add(row);
                }
            }
        } catch (IOException e) {
            errors.add("Error reading file: " + e.getMessage());
        }

        return validRows;
    }

    private List<String> parseAndValidateHeaders(String headerLine, List<String> expectedHeaders, List<String> errors) {
        List<String> actualHeaders = Arrays.stream(headerLine.split(","))
                .map(String::trim)
                .toList();
        List<String> missingHeaders = expectedHeaders.stream()
                .filter(expected -> !actualHeaders.stream().anyMatch(actual -> actual.equalsIgnoreCase(expected)))
                .toList();

        if (!missingHeaders.isEmpty()) {
            errors.add("Missing/MissMatching headers: " + missingHeaders);
        }
        return actualHeaders;
    }

    private Map<String, String> processDataRow(String line, List<String> expectedHeaders, int rowNumber, List<String> errors) {
        String[] tokens = line.split(",", -1); // keep empty strings
        if (tokens.length < expectedHeaders.size()) {
            errors.add("Row " + rowNumber + " is incomplete: expected " + expectedHeaders.size() + " columns.");
            return null;
        }

        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < expectedHeaders.size(); i++) {
            String key = expectedHeaders.get(i);
            String value = i < tokens.length ? tokens[i].trim() : "";
            row.put(key, value);
        }
            List<String> otherEmptyFields = expectedHeaders.stream()
                    .filter(header -> !header.equals(Constants.ACTION_APPROVE_REJECT) &&
                            (row.get(header) == null || row.get(header).trim().isEmpty()))
                    .collect(Collectors.toList());
        String action = row.get(Constants.ACTION_APPROVE_REJECT);
        if (action == null) {
            errors.add("Row " + rowNumber + " is missing 'action' field.");
            return null;
        }
        if (action.trim().isEmpty()) {
            if (!otherEmptyFields.isEmpty()) {
                errors.add("Row " + rowNumber + " has empty required fields: " + otherEmptyFields + " and empty action.");
                return null;
            }
            logger.info("Row {} skipped: 'action' field is empty.", rowNumber);
            return Collections.emptyMap(); // Skip row silently
        }
      
        if (!"approve".equalsIgnoreCase(action) && !"reject".equalsIgnoreCase(action)) {
            errors.add("Row " + rowNumber + " has invalid action: " + action);
            return null;
        }

        if (!otherEmptyFields.isEmpty()) {
                errors.add("Row " + rowNumber + " has empty required fields: " + otherEmptyFields + ", while action is also empty.");
                return null;
        }
        return row;
    }

    private WfRequest buildWfRequest(String wfId, String userId, String action,
                                     WfStatusEntity wfStatus, String contentId) {
        WfRequest wfRequest = new WfRequest();

        wfRequest.setWfId(wfId);
        wfRequest.setUserId(userId);
        wfRequest.setAction(action.toUpperCase());
        wfRequest.setApplicationId(wfStatus.getApplicationId());
        wfRequest.setActorUserId(wfStatus.getActorUUID());
        wfRequest.setServiceName(wfStatus.getServiceName());
        wfRequest.setRootOrgId(wfStatus.getRootOrg());
        wfRequest.setCourseId(contentId);
        wfRequest.setDeptName(wfStatus.getDeptName());
        wfRequest.setState(wfStatus.getCurrentStatus());
        ObjectMapper objectMapper = new ObjectMapper();
        String updateFieldValuesStr = wfStatus.getUpdateFieldValues();
        try {
            List<HashMap<String, Object>> updateFieldValues = objectMapper.readValue(
                    updateFieldValuesStr,
                    new TypeReference<List<HashMap<String, Object>>>() {
                    }
            );
            wfRequest.setUpdateFieldValues(updateFieldValues);
        } catch (IOException e) {
            logger.error("Error parsing updateFieldValues '{}': {}", updateFieldValuesStr, e.getMessage());

        }
        return wfRequest;
    }

    private String writeUpdatedCsv(MultipartFile originalFile, List<String[]> updatedRows) throws IOException {
        String originalFileName = originalFile.getOriginalFilename();
        String updatedFileName = originalFileName.replace(".csv", "_updated.csv");
        File tempFile = new File(System.getProperty("java.io.tmpdir"), updatedFileName);
        try (
                BufferedWriter writer = Files.newBufferedWriter(tempFile.toPath());
                CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
                        .withHeader(Constants.EMAIL, Constants.USER_NAME, Constants.WF_ID_CONSTANT, Constants.USER_ID, Constants.ACTION_APPROVE_REJECT, Constants.ACTION_UPDATED_NOT_UPDATED, Constants.ERROR))
        ) {
            for (String[] row : updatedRows) {
                csvPrinter.printRecord((Object[]) row);
            }
            csvPrinter.flush();
        }
        logger.info("Updated CSV file written to: {}", tempFile.getAbsolutePath());
        return tempFile.getAbsolutePath();
    }

    private void processRow(Map<String, String> row, WfStatusEntity wfStatus, String contentId,
                            List<String[]> updatedRows, SBApiResponse response) {
        String wfId = row.get(Constants.WF_ID_CONSTANT);
        String userId = row.get(Constants.USER_ID);
        String action = row.get(Constants.ACTION_APPROVE_REJECT);
        String userName = row.get(Constants.USER_NAME);
        String emailId = row.get(Constants.EMAIL);

        String resultStatus = Constants.NOT_UPDATED;
        String error = "";

        try {
            WfRequest wfRequest = buildWfRequest(wfId, userId, action, wfStatus, contentId);
            String currentStatus = wfStatus.getCurrentStatus();
            String role;
            if (Constants.SEND_FOR_MDO_APPROVAL.equalsIgnoreCase(currentStatus)) {
                role = Constants.MDO_ADMIN;
            } else if (Constants.SEND_FOR_PC_APPROVAL.equalsIgnoreCase(currentStatus)) {
                role = Constants.PROGRAM_COORDINATOR;
            } else {
                role = "";
            }
            Response updateApprovalResponse = updateBPWorkFlow(
                    wfStatus.getRootOrg(), wfStatus.getOrg(), wfRequest, userId, role
            );

            if (isValidResponse(updateApprovalResponse)) {
                resultStatus = processApprovalStatus(updateApprovalResponse, wfId);
                if (!Constants.UPDATED.equals(resultStatus)) {
                    error = "Unexpected or failed workflow status for wfId " + wfId;
                }
            } else {
                error = "Null or invalid response for wfId " + wfId;
            }

        } catch (Exception e) {
            error = e.getMessage() != null ? e.getMessage() : "Unknown error";
            logger.error("Exception while processing wfId {}: {}", wfId, error, e);
            addFailureToResponse(response, wfId, error);
        }
        updatedRows.add(new String[]{emailId, userName, wfId, userId, action, resultStatus, error});
    }

    private ResponseEntity<?> prepareCsvResponse(MultipartFile file, List<String[]> updatedRows) {
        File tempFile = null;
        try {
            String updatedFileName = writeUpdatedCsv(file, updatedRows);
            tempFile = new File(updatedFileName);
            byte[] fileContent = Files.readAllBytes(tempFile.toPath());
            ByteArrayResource resource = new ByteArrayResource(fileContent);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + tempFile.getName())
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .contentLength(fileContent.length)
                    .body(resource);
        } catch (IOException e) {
            logger.error("Error writing or reading the result CSV", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ByteArrayResource("Error generating result CSV file".getBytes()));
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private boolean isValidResponse(Response response) {
        return response != null && response.getResult() != null;
    }

    private String processApprovalStatus(Response response, String wfId) {
        try {
            logger.debug("Raw response: {}", mapper.writeValueAsString(response));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        Map<String, Object> result = response.getResult();
        logger.debug("Extracted result: {}", result);

        Object statusObj = result.get(Constants.STATUS);
        String statusStr = String.valueOf(statusObj).trim().toUpperCase();
        logger.debug("Status in result: {}", statusStr);

        if (!("OK".equals(statusStr) || "200 OK".equals(statusStr))) {
            logger.error("Workflow transition failed for wfId {}: {}", wfId, statusStr);
            return Constants.NOT_UPDATED;
        }

        Object dataObj = result.get(Constants.DATA);
        logger.debug("Extracted 'data' from result: {}", dataObj);
        if (dataObj instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) dataObj;
            Object wfStatusResp = dataMap.get(Constants.STATUS);
            String wfStatusStr = String.valueOf(wfStatusResp).trim().toUpperCase();
            logger.debug("Workflow 'status' inside 'data' for wfId {}: {}", wfId, wfStatusResp);
            if (Constants.APPROVED_STATE.equalsIgnoreCase(wfStatusStr) || Constants.REJECTED.equalsIgnoreCase(wfStatusStr) || Constants.SEND_FOR_PC_APPROVAL.equalsIgnoreCase(wfStatusStr) || Constants.SEND_FOR_MDO_APPROVAL.equalsIgnoreCase(wfStatusStr)) {
                return Constants.UPDATED;
            } else {
                logger.warn("Unexpected workflow status for wfId {}: {}", wfId, wfStatusResp);
            }
        }
        return Constants.NOT_UPDATED;
    }

    private void addFailureToResponse(SBApiResponse response, String wfId, String error) {
        response.getResult().computeIfAbsent("updateFailures", k -> new ArrayList<Map<String, String>>());
        Map<String, String> failureDetails = new HashMap<>();
        failureDetails.put(Constants.WF_ID_CONSTANT, wfId);
        failureDetails.put("error", error);
        ((List<Map<String, String>>) response.getResult().get("updateFailures")).add(failureDetails);
    }

    @Override
    public Response removeApprovedUser(WfRequest wfRequest, boolean isPc) {
        Response response = new Response();
        String role = isPc ? Constants.PROGRAM_COORDINATOR : Constants.MDO_ADMIN;
        String userId = wfRequest.getUserId();
        logger.info("Initiating workflow REMOVE action by role: {} for user: {}", role, userId);
        String applicationId = wfRequest.getApplicationId();
        String courseId = wfRequest.getCourseId();
        Response validationResponse = validateApprovedUserRemovalRequest(userId, wfRequest, response);
        if (validationResponse != null) {
            return validationResponse;
        }
        List<WfStatusEntity> wfRecords = wfStatusRepo.findWorkflowByBatchAndUser(applicationId, userId);
        if (CollectionUtils.isEmpty(wfRecords)) {
            logger.error("No active workflow found for applicationId: {} and userId: {}", applicationId, userId);
            response.put(Constants.ERROR_MESSAGE, "No active workflow found for the given applicationId and userId.");
            response.put(Constants.STATUS, HttpStatus.NOT_FOUND);
            return response;
        }
        List<WfStatusEntity> approvedRecords = wfRecords.stream()
                .filter(wfRecord -> Constants.APPROVED.equalsIgnoreCase(wfRecord.getCurrentStatus()))
                .toList();
        if (approvedRecords.isEmpty()) {
            logger.warn("No APPROVED workflow entry found for user {} in application {}", userId, applicationId);
            response.put(Constants.ERROR_MESSAGE, "User is not currently in APPROVED state.");
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }

        if (approvedRecords.size() > 1) {
            logger.error("Multiple APPROVED workflow records found for user {} in application {} — data inconsistency!", userId, applicationId);
            response.put(Constants.ERROR_MESSAGE, "Data inconsistency: Multiple APPROVED workflow records found.");
            response.put(Constants.STATUS, HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
        WfStatusEntity approvedRecord = approvedRecords.get(0);
        logger.info("Found APPROVED workflow record. wfId: {}", approvedRecord.getWfId());
        List<HashMap<String, Object>> updateFieldValuesHashMap =
                extractUpdateFieldValues(approvedRecord.getUpdateFieldValues());

        if (CollectionUtils.isEmpty(updateFieldValuesHashMap)) {
            response.put(Constants.ERROR_MESSAGE, "Invalid updateFieldValues format in workflow record.");
            response.put(Constants.STATUS, HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
        wfRequest.setWfId(approvedRecord.getWfId());
        wfRequest.setUserId(userId);
        wfRequest.setActorUserId(userId);
        wfRequest.setAction(Constants.REMOVE);
        wfRequest.setState(Constants.APPROVED);
        wfRequest.setServiceName(approvedRecord.getServiceName());
        wfRequest.setRootOrgId(approvedRecord.getRootOrg());
        wfRequest.setDeptName(approvedRecord.getDeptName());
        wfRequest.setCourseId(courseId);
        wfRequest.setUpdateFieldValues(updateFieldValuesHashMap);
        return updateBPWorkFlow(
                wfRequest.getRootOrgId(),
                approvedRecord.getOrg(),
                wfRequest,
                userId,
                role
        );
    }

    private List<HashMap<String, Object>> extractUpdateFieldValues(String updateFieldValuesJson) {
        try {
            List<Map<String, Object>> parsedList = mapper.readValue(
                    updateFieldValuesJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    }
            );
            return parsedList.stream()
                    .map(HashMap::new)
                    .toList();

        } catch (Exception e) {
            logger.error("Failed to parse update_field_values JSON: {}", updateFieldValuesJson, e);
            return null;
        }
    }

    private Response validateApprovedUserRemovalRequest(String userId, WfRequest wfRequest, Response response) {

        if (StringUtils.isEmpty(wfRequest.getApplicationId())) {
            logger.error("ApplicationId is missing or empty.");
            response.put(Constants.ERROR_MESSAGE, "applicationId must not be empty.");
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        if (StringUtils.isEmpty(userId)) {
            logger.error(" userId is missing.");
            response.put(Constants.ERROR_MESSAGE, "userId must not be empty.");
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        if (StringUtils.isEmpty(wfRequest.getCourseId())) {
            logger.error(" courseId is missing.");
            response.put(Constants.ERROR_MESSAGE, "courseId must not be empty.");
            response.put(Constants.STATUS, HttpStatus.BAD_REQUEST);
            return response;
        }
        return null;
    }

    public Response nominateUsers(String rootOrg, String org, String actorUserId, Map<String, Object> requestBody) {
        logger.info("Nomination Wrapper API triggered by actor: {}", actorUserId);

        Response validationResponse = validateNominationRequest(actorUserId, requestBody);
        if (validationResponse != null) {
            return validationResponse;
        }

        Response response = new Response();
        List<Map<String, Object>> processedUsers = new ArrayList<>();

        try {
            String programId = (String) requestBody.get(Constants.COURSE_ID);
            String batchId = (String) requestBody.get(Constants.BATCH_ID);
            String deptName = (String) requestBody.get(Constants.DEPT_NAME);
            String wfApproveType = contentReadService.getServiceNameDetails(programId);
            if (StringUtils.isBlank(wfApproveType)) {
                wfApproveType = Constants.BLENDED_PROGRAM_SERVICE_NAME;
            }

            @SuppressWarnings("unchecked")
            List<String> userIds = (List<String>) requestBody.get(Constants.USER_IDS);
            Map<String, Object> actorProfile = getUserProfile(actorUserId);
            String incomingRole = extractUserRole(actorProfile);

            if (incomingRole.equalsIgnoreCase("SELF")) {
                return errorResponse("You do not have permission to nominate users",
                        HttpStatus.FORBIDDEN);
            }

            for (String userId : userIds) {

                Map<String, Object> userResponse = new HashMap<>();
                userResponse.put("userId", userId);
                List<WfStatusEntity> activeRequests =
                        wfStatusRepo.findActiveWorkflows(batchId, userId, Boolean.TRUE);
                boolean blockNewNomination = false;
                if (CollectionUtils.isNotEmpty(activeRequests)) {
                    for (WfStatusEntity req : activeRequests) {
                        String existingRole;
                        if (StringUtils.isBlank(req.getActorUUID())) {
                            logger.warn("ActorUUID missing for workflow {}, treating as SELF", req.getWfId());
                            existingRole = Constants.SELF;
                        } else {
                            Map<String, Object> creatorProfile = getUserProfile(req.getActorUUID());
                            existingRole = extractUserRole(creatorProfile);
                        }

                        logger.info("Workflow override check - existing={}, incoming={} for userId={}",
                                existingRole, incomingRole, userId);

                        if (shouldOverride(existingRole, incomingRole)) {
                            req.setInWorkflow(false);
                            req.setCurrentStatus("WITHDRAWN");
                            req.setComment("Superseded by " + incomingRole + " nomination");
                            req.setLastUpdatedOn(new Date());
                            wfStatusRepo.save(req);
                        } else {
                            logger.info("Skipping nomination - existing approval by {} remains active", existingRole);
                            userResponse.put(Constants.STATUS, Constants.ALREADY_EXISTS);
                            processedUsers.add(userResponse);
                            blockNewNomination = true;
                            break;
                        }
                    }
                }

                if (blockNewNomination) {
                    continue;
                }

                WfRequest wfRequest = new WfRequest();
                wfRequest.setApplicationId(batchId);
                wfRequest.setCourseId(programId);
                wfRequest.setUserId(userId);
                wfRequest.setActorUserId(actorUserId);
                wfRequest.setDeptName(deptName);
                wfRequest.setServiceName(wfApproveType);
                wfRequest.setUpdateFieldValues(buildUpdateFieldValuesWithFirstName(userId));

                switch (wfApproveType) {
                    case Constants.ONE_STEP_PC_APPROVAL:
                    case Constants.TWO_STEP_MDO_AND_PC_APPROVAL:
                        wfRequest.setState(Constants.SEND_FOR_PC_APPROVAL);
                        wfRequest.setAction(Constants.APPROVE);
                        break;

                    case Constants.ONE_STEP_MDO_APPROVAL:
                    case Constants.TWO_STEP_PC_AND_MDO_APPROVAL:
                        wfRequest.setState(Constants.SEND_FOR_MDO_APPROVAL);
                        wfRequest.setAction(Constants.APPROVE);
                        break;

                    default:
                        logger.warn("Invalid wfApproveType: {}", wfApproveType);
                        userResponse.put(Constants.STATUS, Constants.INVALID_APPROVAL_TYPE);
                        processedUsers.add(userResponse);
                        continue;
                }

                Map<String, Object> batchDetailsMap = new HashMap<>();
                String validationError = validateBatchUserRequestAccess(wfRequest, batchDetailsMap);
                wfRequest.setBatchName((String) batchDetailsMap.get(Constants.BATCH_NAME));
                wfRequest.setBatchStartDate((Date) batchDetailsMap.get(Constants.START_DATE));

                if (StringUtils.isNotBlank(validationError)) {
                    userResponse.put(Constants.STATUS, validationError);
                    processedUsers.add(userResponse);
                    continue;
                }

                if (CollectionUtils.isNotEmpty(getActiveEnrollmentForUserAndCourse(userId, programId))) {
                    userResponse.put(Constants.STATUS, Constants.ALREADY_EXISTS);
                    processedUsers.add(userResponse);
                    continue;
                }

                if (scheduleConflictCheck(wfRequest)) {
                    userResponse.put(Constants.STATUS, "SCHEDULE_CONFLICT");
                    processedUsers.add(userResponse);
                    continue;
                }
                WfStatusEntity entity = persistApprovedStateDirectly(wfRequest, rootOrg, org);
                wfRequest.setWfId(entity.getWfId());
                try {
                    producer.push(configuration.getWorkFlowNotificationTopic(), wfRequest);
                    producer.push(configuration.getWorkflowApplicationTopic(), wfRequest);
                } catch (Exception ex) {
                    logger.error("Kafka publishing failed for userId={}", userId, ex);
                }
                userResponse.put("status", Constants.APPROVED);
                userResponse.put("wfId", entity.getWfId());
                processedUsers.add(userResponse);
            }

            response.put(Constants.MESSAGE, "Nomination workflow processing complete");
            response.put(Constants.DATA, processedUsers);
            response.put(Constants.STATUS, HttpStatus.OK);
            return response;
        } catch (Exception e) {
            logger.error("Error processing nomination", e);
            return errorResponse(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<HashMap<String, Object>> buildUpdateFieldValuesWithFirstName(String userId) {
        HashMap<String, Object> toValue = new HashMap<>();
        HashMap<String, Object> updateField = new HashMap<>();
        String firstName = getFirstName(userId);
        toValue.put(Constants.NAME, firstName != null ? firstName : userId);
        updateField.put(Constants.TO_VALUE, toValue);
        return Collections.singletonList(updateField);
    }

    private WfStatusEntity persistApprovedStateDirectly(WfRequest wfRequest, String rootOrg, String org) {
        WfStatusEntity entity = new WfStatusEntity();
        String wfId = UUID.randomUUID().toString();
        entity.setWfId(wfId);
        entity.setApplicationId(wfRequest.getApplicationId());
        entity.setUserId(wfRequest.getUserId());
        entity.setServiceName(wfRequest.getServiceName());
        entity.setDeptName(wfRequest.getDeptName());
        entity.setCurrentStatus(Constants.APPROVED);
        entity.setInWorkflow(true);
        entity.setCreatedOn(new Date());
        entity.setLastUpdatedOn(new Date());
        entity.setRootOrg(rootOrg);
        entity.setOrg(org);
        entity.setActorUUID(wfRequest.getActorUserId());
        try {
            entity.setUpdateFieldValues(mapper.writeValueAsString(wfRequest.getUpdateFieldValues()));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize updateFieldValues", e);
        }
        entity.setComment("Auto-approved via nomination wrapper (PC final)");

        WfStatusEntity saved = wfStatusRepo.save(entity);
        return saved;
    }

    private boolean isExistingWorkflowPresent(String batchId, String userId) {
        List<WfStatusEntity> existingRecords = wfStatusRepo.findActiveWorkflows(batchId, userId, Boolean.TRUE);
        boolean exists = CollectionUtils.isNotEmpty(existingRecords);
        if (exists) {
            logger.warn("Active workflow already exists for userId: {} batchId: {}", userId, batchId);
        }
        return exists;
    }

    private String getFirstName(String userId) {
        Map<String, Object> userData = userUtils.userProfileRead(userId);

        if (MapUtils.isNotEmpty(userData)) {
            Object firstName = userData.get(Constants.FIRST_NAME_CAMEL_CASE);
            if (firstName != null) {
                return firstName.toString().trim();
            }
        }
        return userId;
    }

    public List<Map<String, Object>> getActiveEnrollmentForUserAndCourse(String userId, String courseId) {

        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.USER_ID, userId);
        propertyMap.put(Constants.COURSE_ID, courseId);

        List<Map<String, Object>> allEnrollmentDetails = cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.USER_ENROLMENTS_V2,
                propertyMap,
                Arrays.asList(Constants.ACTIVE)
        );

        return allEnrollmentDetails.stream()
                .filter(e -> e != null
                        && e.get(Constants.ACTIVE) != null
                        && (boolean) e.get(Constants.ACTIVE))
                .collect(Collectors.toList());
    }


    private Response validateNominationRequest(String actorUserId, Map<String, Object> requestBody) {

        String programId = (String) requestBody.get(Constants.COURSE_ID);
        if (StringUtils.isBlank(programId))
            return errorResponse("Program ID is required", HttpStatus.BAD_REQUEST);

        String batchId = (String) requestBody.get(Constants.BATCH_ID);
        if (StringUtils.isBlank(batchId))
            return errorResponse("Batch ID is required", HttpStatus.BAD_REQUEST);

        String deptName = (String) requestBody.get(Constants.DEPT_NAME);
        if (StringUtils.isBlank(deptName))
            return errorResponse("Department is required", HttpStatus.BAD_REQUEST);

        @SuppressWarnings("unchecked")
        List<String> userIds = (List<String>) requestBody.get(Constants.USER_IDS);
        if (CollectionUtils.isEmpty(userIds))
            return errorResponse("User list cannot be empty", HttpStatus.BAD_REQUEST);

        userIds = userIds.stream()
                .filter(StringUtils::isNotBlank)
                .filter(id -> !id.equals(actorUserId))
                .distinct()
                .toList();

        if (userIds.isEmpty())
            return errorResponse("Invalid user list", HttpStatus.BAD_REQUEST);

        if (userIds.size() > 200)
            return errorResponse("Max 200 users allowed", HttpStatus.BAD_REQUEST);

        requestBody.put(Constants.USER_IDS, userIds);
        return null;
    }

    private Map<String, Object> getUserProfile(String userId) {
        Map<String, Object> userData = userUtils.userProfileRead(userId);
        return MapUtils.isNotEmpty(userData) ? userData : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private String extractUserRole(Map<String, Object> profile) {
        try {
            List<Map<String, Object>> orgs = (List<Map<String, Object>>) profile.get(Constants.ORGANISATIONS);
            if (orgs != null) {
                for (Map<String, Object> org : orgs) {
                    List<String> roles = (List<String>) org.get(Constants.ROLES);
                    if (roles != null) {

                        if (roles.contains(Constants.PROGRAM_COORDINATOR)) {
                            return Constants.PC;
                        }

                        if (roles.contains(Constants.MDO_ADMIN) || roles.contains(Constants.MDO_LEADER)) {
                            return Constants.MDO;
                        }
                    }
                }
            }
        } catch (Exception ignore) {}

        return Constants.SELF;
    }

    private boolean shouldOverride(String existingRole, String incomingRole) {
        if (existingRole.equalsIgnoreCase(incomingRole)) {
            logger.info("Same role override not allowed: existing={} incoming={}", existingRole, incomingRole);
            return false;
        }

        if (incomingRole.equalsIgnoreCase(Constants.PC)) {
            logger.info("PC override allowed: existing={} incoming={}", existingRole, incomingRole);
            return true;
        }

        if (incomingRole.equalsIgnoreCase(Constants.MDO) && existingRole.equalsIgnoreCase(Constants.SELF)) {
            logger.info("MDO override allowed: existing={} incoming={}", existingRole, incomingRole);
            return true;
        }

        logger.info("Override not allowed: existing={} incoming={}", existingRole, incomingRole);
        return false;
    }

    private Response errorResponse(String msg, HttpStatus status) {
        Response res = new Response();
        res.put(Constants.ERROR_MESSAGE, msg);
        res.put(Constants.STATUS, status);
        return res;
    }

    private void reverseApprovedRequest(WfRequest wfRequest) {
        WfStatusEntity applicationStatus = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(), wfRequest.getWfId());
        if (applicationStatus != null && applicationStatus.getCreatedOn() != null) {
            wfRequest.setCreatedOn(applicationStatus.getCreatedOn().toString());
        }
        applicationStatus.setLastUpdatedOn(new Date());
        applicationStatus.setCurrentStatus(Constants.SEND_FOR_PC_APPROVAL);
        WfStatusEntity savedEntity = wfStatusRepo.save(applicationStatus);
    }

}
