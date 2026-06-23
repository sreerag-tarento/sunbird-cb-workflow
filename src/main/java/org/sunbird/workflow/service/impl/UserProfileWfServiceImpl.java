package org.sunbird.workflow.service.impl;

import java.text.SimpleDateFormat;
import java.util.*;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.client.RestTemplate;
import org.sunbird.workflow.config.Configuration;
import org.sunbird.workflow.config.Constants;
import org.sunbird.workflow.config.RedisCacheMgr;
import org.sunbird.workflow.exception.ApplicationException;
import org.sunbird.workflow.models.WfRequest;
import org.sunbird.workflow.postgres.entity.WfStatusEntity;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.service.UserProfileWfService;
import org.sunbird.workflow.service.Workflowservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

@Service
public class UserProfileWfServiceImpl implements UserProfileWfService {

	private Logger logger = LoggerFactory.getLogger(UserProfileWfServiceImpl.class);


	@Autowired
	private RequestServiceImpl requestServiceImpl;

	@Autowired
	private Configuration configuration;

	@Autowired
	private RestTemplate restTemplate;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private WfStatusRepo wfStatusRepo;

	@Autowired
	@Qualifier("workflowServiceImpl")
	WorkflowServiceImpl workflowService;

	@Autowired
	private WorkflowAuditProcessingServiceImpl workflowAuditProcessingService;

	@Autowired
	private RedisCacheMgr redisCacheMgr;

    private final List<String> stateOrMinistry = Arrays.asList("16", "2048");
	/**
	 * Update user profile based on wf request
	 *
	 * @param wfRequest Workflow request
	 */
	public void updateUserProfile(WfRequest wfRequest) {
		WfStatusEntity wfStatusEntity = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(),
				wfRequest.getWfId());
		if (Constants.PROFILE_SERVICE_NAME.equals(wfRequest.getServiceName())
				&& Constants.APPROVED_STATE.equals(wfStatusEntity.getCurrentStatus())) {
			updateProfile(wfRequest);
		}
		if (Constants.USER_PROFILE_FLAG_SERVICE.equals(wfRequest.getServiceName())
				&& Constants.PROCESSED_STATE.equals(wfStatusEntity.getCurrentStatus())) {
			updateProfile(wfRequest);
		}
	}

	private void updateProfile(WfRequest wfRequest) {
		try {
			String deptNameUpdated = migrationUpdate(wfRequest);
			Map<String, Object> readData = (Map<String, Object>) userProfileRead(wfRequest.getApplicationId());
			if (null != readData && !Constants.OK.equals(readData.get(Constants.RESPONSE_CODE))) {
				logger.error("user not found" + ((Map<String, Object>) readData.get(Constants.PARAMS)).get(Constants.ERROR_MESSAGE));
				failedCase(wfRequest);
				return;
			}
			Map<String, Object> existingUserResults = (Map<String, Object>) readData.get(Constants.RESULT);
			Map<String, Object> existingUserResponse = (Map<String, Object>) existingUserResults.get(Constants.RESPONSE);
			Map<String, Object> profileDetails = (Map<String, Object>) existingUserResponse.get(Constants.PROFILE_DETAILS);
			Map<String, Object> rootOrg = (Map<String, Object>) existingUserResponse.get(Constants.ROOT_ORG_CONSTANT);
			String rootOrgId = (String) rootOrg.get(Constants.ROOT_ORG_ID);

			if (null != deptNameUpdated) {
				boolean assignFlag = assignRole(rootOrgId, wfRequest.getApplicationId());
				if (!assignFlag) {
					logger.error("Failed to assign PUBLIC role to user after Migration");
					failedCase(wfRequest);
					return;
				}
				Map<String, Object> employmentDetails = (Map<String, Object>) profileDetails.get(Constants.EMPLOYMENT_DETAILS);
				employmentDetails.put(Constants.DEPARTMENT_NAME, deptNameUpdated);
			}

			Map<String, Object> updateRequest = updateRequestWithWF(wfRequest.getApplicationId(), wfRequest.getUpdateFieldValues(), profileDetails);
			if (null == updateRequest) {
				logger.error("user profile datatype error");
				failedCase(wfRequest);
				return;
			}
			//This field is updated via approval
			/*if (validateJsonAgainstSchema(updateRequest)) {
				updateRequest.put(Constants.VERIFIED_KARMAYOGI, true);
			} else {
				updateRequest.put(Constants.VERIFIED_KARMAYOGI, false);
			}*/
			Map<String, Object> updateUserApiResp = requestServiceImpl
					.fetchResultUsingPatch(configuration.getLmsServiceHost() + configuration.getUserProfileUpdateEndPoint(), getUpdateRequest(wfRequest, updateRequest), getHeaders());
			if (null != updateUserApiResp && !Constants.OK.equals(updateUserApiResp.get(Constants.RESPONSE_CODE))) {
				logger.error("user update failed" + ((Map<String, Object>) updateUserApiResp.get(Constants.PARAMS)).get(Constants.ERROR_MESSAGE));
				failedCase(wfRequest);
			} else {
				String cacheKey = Constants.USER_BASIC_PROFILE_REDIS_KEY_PREFIX + existingUserResponse.get(Constants.USER_ID);
				logger.info("User profile updated. Invalidating basicProfile cache for userId: {}", existingUserResponse.get(Constants.USER_ID));
				redisCacheMgr.deleteCache(cacheKey);
			}
		} catch (Exception e) {
			logger.error("Exception occurred : ", e);
		}
	}

	public Map<String, Object> updateRequestWithWF(String uuid, List<HashMap<String, Object>> wfRequestParamList, Map<String, Object> existingProfileDetail) {
		Map<String, Object> exitingProfileDetailCopy = deepCopyObject(existingProfileDetail);
		try {
			// merge wfRequestParamList and existingProfileDetail to add osid(s)
			for (Map<String, Object> wfRequestParamObj : wfRequestParamList) {
				String osid = wfRequestParamObj.get(Constants.OSID) == null ? "" : wfRequestParamObj.get(Constants.OSID).toString();
				Map<String, Object> updatedProfileElement = new HashMap<>();
				Object updatedProfileElementObj = existingProfileDetail.get(wfRequestParamObj.get(Constants.FIELD_KEY));
				if (updatedProfileElementObj instanceof ArrayList) {
					List<Map<String, Object>> existingProfileElementList = mapper.convertValue(updatedProfileElementObj, ArrayList.class);
					for (Map<String, Object> existingProfileElement : existingProfileElementList) {
						if (existingProfileElement.get(Constants.OSID) != null
								&& existingProfileElement.get(Constants.OSID).toString().equalsIgnoreCase(osid))
							updatedProfileElement.putAll(existingProfileElement);
					}
				} else if (updatedProfileElementObj instanceof HashMap) {
					Map<String, Object> existingProfileElementList = mapper.convertValue(updatedProfileElementObj, Map.class);
					updatedProfileElement.putAll(existingProfileElementList);
				} else if (updatedProfileElementObj instanceof Boolean) {
					Map<String,Object> toValueMap = (Map<String, Object>) wfRequestParamObj.get(Constants.TO_VALUE);
					existingProfileDetail.put((String) wfRequestParamObj.get(Constants.FIELD_KEY), (Boolean) toValueMap.get(Constants.VERIFIED_KARMAYOGI));
				} else if (null == updatedProfileElementObj) {
					if (Constants.VERIFIED_KARMAYOGI.equalsIgnoreCase((String) wfRequestParamObj.get(Constants.FIELD_KEY))) {
						Map<String,Object> toValueMap = (Map<String, Object>) wfRequestParamObj.get(Constants.TO_VALUE);
						existingProfileDetail.put((String) wfRequestParamObj.get(Constants.FIELD_KEY), (Boolean) toValueMap.get(Constants.VERIFIED_KARMAYOGI));
					} else if (Constants.PROFESSIONAL_DETAILS.equalsIgnoreCase((String) wfRequestParamObj.get(Constants.FIELD_KEY))) {
						List<Map<String, Object>> detailsList = new ArrayList<>();
						Map<String, Object> detailsMap = new HashMap<>();
						detailsMap = (Map<String, Object>) wfRequestParamObj.get(Constants.TO_VALUE);
						detailsList.add(detailsMap);
						existingProfileDetail.put((String) wfRequestParamObj.get(Constants.FIELD_KEY), detailsList);
					} else if (Constants.ADDITIONAL_PROPERTIES.equalsIgnoreCase((String) wfRequestParamObj.get(Constants.FIELD_KEY))) {
                        Map<String, Object> additionalMap = (Map<String, Object>) wfRequestParamObj.get(Constants.TO_VALUE);
                        existingProfileDetail.put((String) wfRequestParamObj.get(Constants.FIELD_KEY), additionalMap);
                    }else {
						logger.error("profile element to be updated is neither arraylist nor hashmap");
						return null;
					}
				}
				Map<String, Object> objectMap = (Map<String, Object>) wfRequestParamObj.get(Constants.TO_VALUE);
				for (Map.Entry entry : objectMap.entrySet())
					updatedProfileElement.put((String) entry.getKey(), entry.getValue());
				mergeLeaf(existingProfileDetail, updatedProfileElement, wfRequestParamObj.get("fieldKey").toString(), osid);
			}
		} catch (Exception e) {
			logger.error("Merge profile exception::{}", e);
		}
		List<Map<String, Object>> professionDetailsInfoList = (List<Map<String, Object>>)existingProfileDetail.get(Constants.PROFESSIONAL_DETAILS);
		if(CollectionUtils.isNotEmpty(professionDetailsInfoList)) {
			Map<String, Object> professionalDetailMap = professionDetailsInfoList.get(0);
			String updatedGroup = null;
			String updatedDesignation = null;
			if (MapUtils.isNotEmpty(professionalDetailMap)) {
				updatedGroup = (String) professionalDetailMap.get(Constants.GROUP);
				updatedDesignation = (String) professionalDetailMap.get(Constants.DESIGNATION);
			}
			if (StringUtils.isNotEmpty(updatedGroup)) {
				existingProfileDetail.put(Constants.PROFILE_GROUP_STATUS, Constants.VERIFIED);
			} else {
				existingProfileDetail.put(Constants.PROFILE_GROUP_STATUS, Constants.NOT_VERIFIED);
			}

			if (StringUtils.isNotEmpty(updatedDesignation)) {
				existingProfileDetail.put(Constants.PROFILE_DESIGNATION_STATUS, Constants.VERIFIED);
			} else {
				existingProfileDetail.put(Constants.PROFILE_DESIGNATION_STATUS, Constants.NOT_VERIFIED);
			}

			if (StringUtils.isNotEmpty(updatedGroup) && StringUtils.isNotEmpty(updatedDesignation)) {
				existingProfileDetail.put(Constants.PROFILE_STATUS, Constants.VERIFIED);
			} else {
				existingProfileDetail.put(Constants.PROFILE_STATUS, Constants.NOT_VERIFIED);
			}

			SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH.mm.ss");
			sdf.setTimeZone(TimeZone.getTimeZone("GMT+05:30"));
			String timeStamp = sdf.format(new java.util.Date());
			existingProfileDetail.put(Constants.PROFILE_STATUS_UPDATED_ON, timeStamp);
			Map<String, Object> additionalProperties = (Map<String, Object>) existingProfileDetail.get(Constants.ADDITIONAL_PROPERTIES);
			if (ObjectUtils.isEmpty(additionalProperties)) {
				additionalProperties = new HashMap<>();
			}
			additionalProperties.put(Constants.PROFILE_STATUS_UPDATED_MSG_VIEWED, false);
		}

		return existingProfileDetail;
	}

	public static void mergeLeaf(Map<String, Object> mapLeft, Map<String, Object> mapRight, String leafKey, String id) {
		if (mapLeft.containsKey(leafKey)) {
			for (String key : mapLeft.keySet()) {
				if (key.equalsIgnoreCase(leafKey) && mapLeft.get(key) instanceof ArrayList) {
					Set<String> childRequest = mapRight.keySet();
					for (String keys : childRequest) {
						List<Map<String, Object>> childExisting = (List<Map<String, Object>>) mapLeft.get(key);
						Map<String, Object> childExistingIndex = (Map<String, Object>) childExisting.get(0);
						childExistingIndex.put(keys, mapRight.get(keys));
					}
				}
				if (key.equalsIgnoreCase(leafKey) && (mapLeft.get(key) instanceof HashMap)) {
					mapLeft.put(key, mapRight);
				}
			}
		} else {
			if (leafKey.equals(Constants.PROFESSIONAL_DETAILS)) {
				List<Map<String, Object>> professionalData = new ArrayList<>();
				Map<String, Object> professionalDataChild = new HashMap<>();
				Set<String> childRequest = mapRight.keySet();
				for (String keys : childRequest) {
					professionalDataChild.put(keys, mapRight.get(keys));
				}
				professionalData.add(professionalDataChild);
				mapLeft.put(Constants.PROFESSIONAL_DETAILS, professionalData);
			}
		}
	}

	/**
	 *
	 * @param wfInfos
	 * @param rootOrg
	 * @return
	 */
	public List<Map<String, Object>> enrichUserData(Map<String, List<WfStatusEntity>> wfInfos, String rootOrg) {
		List<Map<String, Object>> wfDetails = new ArrayList<>();
		HashMap<String, Object> responseMap;
		HashMap<String, Object> userResult = null;
		Set<String> userIds = wfInfos.keySet();
		if (!CollectionUtils.isEmpty(userIds)) {
			List<String> sources = new ArrayList<>(Constants.USER_DEFAULT_FIELDS);
			if (!sources.contains(Constants.UUID)) {
				sources.add(Constants.UUID);
			}
			userResult = getUsersResult(userIds);
		}
		for (Map.Entry<String, List<WfStatusEntity>> wfStatusEntity : wfInfos.entrySet()) {
			responseMap = new HashMap<>();
			responseMap.put("wfInfo", wfStatusEntity.getValue());
			responseMap.put("userInfo", userResult.get(wfStatusEntity.getKey()));
			wfDetails.add(responseMap);
		}
		return wfDetails;
	}

	public HashMap<String, Object> getUsersResult(Set<String> userIds) {
		HashMap<String, Object> userResult = new HashMap<>();
		Map<String, Object> request = getSearchObject(userIds);
		HashMap<String, String> headersValue = new HashMap<>();
		headersValue.put("Content-Type", "application/json");
		try {
			StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
			builder.append(configuration.getLmsUserSearchEndPoint());
			Map<String, Object> searchProfileApiResp = (Map<String, Object>) requestServiceImpl
					.fetchResultUsingPost(builder, request, Map.class, getHeaders());
			if (searchProfileApiResp != null
					&& "OK".equalsIgnoreCase((String) searchProfileApiResp.get(Constants.RESPONSE_CODE))) {
				Map<String, Object> map = (Map<String, Object>) searchProfileApiResp.get(Constants.RESULT);
				Map<String, Object> response = (Map<String, Object>) map.get(Constants.RESPONSE);
				List<Map<String, Object>> contents = (List<Map<String, Object>>) response.get(Constants.CONTENT);
				if (!CollectionUtils.isEmpty(contents)) {
					for (Map<String, Object> content : contents) {
						HashMap<String, Object> profileDetails = (HashMap<String, Object>) content
								.get(Constants.PROFILE_DETAILS);
						if (MapUtils.isNotEmpty(profileDetails)) {
							HashMap<String, Object> personalDetails = (HashMap<String, Object>) profileDetails
									.get(Constants.PERSONAL_DETAILS);
							Map<String, Object> record = new HashMap<>();
							if (MapUtils.isNotEmpty(personalDetails)) {
								record.put(Constants.UUID, content.get(Constants.USER_ID));
								record.put(Constants.FIRST_NAME, personalDetails.get(Constants.FIRSTNAME));
								record.put(Constants.EMAIL, personalDetails.get(Constants.PRIMARY_EMAIL));
								record.put(Constants.ROOT_ORG_ID,content.get(Constants.ROOT_ORG_ID));
								record.put(Constants.MOBILE, personalDetails.get(Constants.MOBILE));
							}
							Map<String, Object> additionalProperties = (Map<String, Object>) profileDetails.get(Constants.ADDITIONAL_PROPERTIES);
							if (MapUtils.isNotEmpty(additionalProperties)) {
								record.put(Constants.TAG, additionalProperties.get(Constants.TAG));
							}
							userResult.put((String) content.get(Constants.USER_ID), record);
						}
					}
				}
			}
		} catch (Exception e) {
			logger.error("Exception while fetching user setails : ",e);
			throw new ApplicationException("Hub Service ERROR: ", e);
		}
		return userResult;
	}

	private Object userProfileRead(String userId) {
		StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
		String endPoint = configuration.getUserProfileReadEndPoint().replace(Constants.USER_ID_VALUE, userId);
		builder.append(endPoint);
		Object response = requestServiceImpl.fetchResultUsingGet(builder);
		if (null == response){
			return null;
		}
		Map<String, Object> readResponse = mapper.convertValue(response, Map.class);
		return readResponse;
	}

	private Object migrateUser(WfRequest wfRequest) {

		Map<String, Object> migrateRequestWrapper = new HashMap<>();
		Map<String, Object> migrateRequestObject = new HashMap<>();
		migrateRequestWrapper.put(Constants.USER_ID, wfRequest.getApplicationId());
		migrateRequestWrapper.put(Constants.CHANNEL, wfRequest.getDeptName());
		migrateRequestWrapper.put(Constants.FORCE_MIGRATION, true);
		migrateRequestWrapper.put(Constants.SOFT_DELETE_OLD_GROUP, true);
		migrateRequestWrapper.put(Constants.NOTIFY_MIGRATION, false);
		migrateRequestObject.put(Constants.REQUEST, migrateRequestWrapper);
		Map<String, Object> migrateUserApiResp = requestServiceImpl
				.fetchResultUsingPatch(configuration.getLmsServiceHost() + configuration.getUserProfileMigrateEndPoint(), migrateRequestObject, getHeaders());
		return migrateUserApiResp;
	}

	private String migrationUpdate(WfRequest wfRequest) {

		String updatedDeptName = null;
		String existingDeptName = null;
		List<HashMap<String, Object>> updatedFieldValues = wfRequest.getUpdateFieldValues();
		HashMap<String, Object> updatedFieldValueElement = updatedFieldValues.get(0);
		HashMap<String, Object> toValueList = (HashMap<String, Object>) updatedFieldValueElement.get(Constants.TO_VALUE);
		HashMap<String, Object> fromValueList = (HashMap<String, Object>) updatedFieldValueElement.get(Constants.FROM_VALUE);
		String fieldKeyValue = (String) updatedFieldValueElement.get(Constants.FIELD_KEY);
		for (String key : fromValueList.keySet()) {
			if (Constants.NAME.equals(key)) {
				existingDeptName = (String) fromValueList.get(Constants.NAME);
			}
		}
		for (String key : toValueList.keySet()) {
			if (Constants.NAME.equals(key)) {
				updatedDeptName = (String) toValueList.get(Constants.NAME);
			}
		}
		if (Constants.PROFESSIONAL_DETAILS.equals(fieldKeyValue) && !StringUtils.isEmpty(updatedDeptName)) {
			wfRequest.setDeptName(updatedDeptName);
			Map<String, Object> response = (Map<String, Object>) migrateUser(wfRequest);
			if (null != response && !Constants.OK.equals(response.get(Constants.RESPONSE_CODE))) {
				logger.error("Migrate user failed" + ((Map<String, Object>) response.get(Constants.PARAMS)).get(Constants.ERROR_MESSAGE));
				failedCase(wfRequest);
				return null;
			} else {
				Map<String, Object> request = new HashMap<>();
				Map<String, Object> requestBody = new HashMap<>();
				requestBody.put(Constants.USER_ID, wfRequest.getApplicationId());
				requestBody.put(Constants.DEPARTMENT_NAME, updatedDeptName);
				request.put(Constants.REQUEST, requestBody);
				workflowService.updatePendingRequestsToNewMDO(request);
			}
		}
		return updatedDeptName;
	}

	public boolean assignRole(String sbOrgId, String userId) {
		boolean retValue = false;
		Map<String, Object> request = new HashMap<>();
		Map<String, Object> requestBody = new HashMap<String, Object>();
		requestBody.put(Constants.ORGANIZATION_ID, sbOrgId);
		requestBody.put(Constants.USER_ID, userId);
		requestBody.put(Constants.ROLES, Arrays.asList(Constants.PUBLIC));
		request.put(Constants.REQUEST, requestBody);
		StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
		builder.append(configuration.getLmsAssignRoleEndPoint());
		Map<String, Object> readData = (Map<String, Object>) requestServiceImpl
				.fetchResultUsingPost(builder, request,Map.class, getHeaders());
		if (Constants.OK.equalsIgnoreCase((String) readData.get(Constants.RESPONSE_CODE))) {
			retValue = true;
		}
		return retValue;
	}

	private void failedCase(WfRequest wfRequest) {
		WfStatusEntity wfStatusEntity = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(),
				wfRequest.getWfId());
		wfRequest.setState(Constants.FAILED);
		wfRequest.setAction(Constants.FAILED);
		wfStatusEntity.setLastUpdatedOn(new Date());
		wfStatusEntity.setCurrentStatus(Constants.FAILED);
		wfStatusEntity.setInWorkflow(false);
		wfStatusRepo.save(wfStatusEntity);
	}

	private Map<String, Object> getUpdateRequest(WfRequest wfRequest, Map<String, Object> updateRequest) {
		Map<String, Object> requestObject = new HashMap<>();
		Map<String, Object> requestWrapper = new HashMap<>();
		List<HashMap<String, Object>> valuesToBeUpdate = wfRequest.getUpdateFieldValues();
		for (Map<String, Object> valueToUpdate : valuesToBeUpdate) {
			if(valueToUpdate.containsKey(Constants.TO_VALUE)) {
				Map<String, Object> toValue = (Map<String, Object>) valueToUpdate.get(Constants.TO_VALUE);
				String keyToUpdate = toValue.keySet().stream().findFirst().get();
				if (Constants.FIRSTNAME.equalsIgnoreCase(keyToUpdate)) {
					requestWrapper.put(Constants.FIRST_NAME_CAMEL_CASE, toValue.get(keyToUpdate));
				}
				if (Constants.MOBILE.equalsIgnoreCase(keyToUpdate)) {
					requestWrapper.put(Constants.PHONE, String.valueOf(toValue.get(keyToUpdate)));
				}
			}
		}
        Map<String, Object> orgDetails = fetchOrgDetails(wfRequest.getDeptName());
        if (MapUtils.isNotEmpty(orgDetails)) {
            Object organisationTypeObj = orgDetails.get(Constants.ORGANISATION_TYPE);
            if (org.apache.commons.lang3.ObjectUtils.isNotEmpty(organisationTypeObj)) {
                String organisationType = organisationTypeObj.toString();
                if (stateOrMinistry.contains(organisationType)) {
                    updateRequest.put(Constants.MINISTRYORSTATEID, String.valueOf(orgDetails.get(Constants.ROOT_ORG_ID)));
                    updateRequest.put(Constants.MINISTRYORSTATEORGNAME, String.valueOf(orgDetails.get(Constants.ORG_NAME)));
                } else if (Constants.SPV_ORGTYPEVALUE.equalsIgnoreCase(organisationType)) {
                    logger.warn("Organisation type is SPV, hence not updating ministry or state details");
                } else {
                    updateRequest.put(Constants.MINISTRYORSTATEID, String.valueOf(orgDetails.get(Constants.MINISTRYORSTATEID)));
                    updateRequest.put(Constants.MINISTRYORSTATEORGNAME, String.valueOf(orgDetails.get(Constants.MINISTRYORSTATENAME)));
                }
            }
        } else {
            logger.warn("Organisation details not found for deptName: {}",wfRequest.getDeptName());
        }
		requestWrapper.put(Constants.USER_ID, wfRequest.getApplicationId());
		requestWrapper.put(Constants.PROFILE_DETAILS, updateRequest);
		requestObject.put(Constants.REQUEST, requestWrapper);
		return requestObject;
	}

	private HashMap<String, String> getHeaders() {
		HashMap<String, String> headersValue = new HashMap<>();
		headersValue.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
		return headersValue;
	}

	private Map<String, Object> getSearchObject(Set<String> userIds) {
		Map<String, Object> requestObject = new HashMap<>();
		Map<String, Object> request = new HashMap<>();
		Map<String, Object> filters = new HashMap<>();
		filters.put("userId", userIds);
		request.put("limit", userIds.size());
		request.put("offset", 0);
		request.put("filters", filters);
		requestObject.put("request", request);
		return requestObject;
	}

	public List<String> getMdoAdminAndPCDetails(String rootOrgId, List<String> roles) {
		List<String> mdoResults = new ArrayList<>();
		Map<String, Object> requestObject = new HashMap<>();
		Map<String, Object> request = new HashMap<>();
		Map<String, Object> filters = new HashMap<>();
		if (StringUtils.isNotBlank(rootOrgId)) {
			filters.put("rootOrgId", rootOrgId);
		}
		filters.put("organisations.roles", roles);
		request.put("filters", filters);
		request.put(Constants.FIELDS, configuration.getMdoAdminSearchFields());
		requestObject.put("request", request);
		HashMap<String, String> headersValue = new HashMap<>();
		headersValue.put("Content-Type", "application/json");
		try {
			StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
			builder.append(configuration.getLmsUserSearchEndPoint());
			Map<String, Object> mdoAdminSearchResult = (Map<String, Object>) requestServiceImpl
					.fetchResultUsingPost(builder, requestObject, Map.class, getHeaders());
			if (mdoAdminSearchResult != null
					&& "OK".equalsIgnoreCase((String) mdoAdminSearchResult.get(Constants.RESPONSE_CODE))) {
				Map<String, Object> map = (Map<String, Object>) mdoAdminSearchResult.get(Constants.RESULT);
				Map<String, Object> response = (Map<String, Object>) map.get(Constants.RESPONSE);
				List<Map<String, Object>> contents = (List<Map<String, Object>>) response.get(Constants.CONTENT);
				if (!CollectionUtils.isEmpty(contents)) {
					for (Map<String, Object> content : contents) {
						HashMap<String, Object> profileDetails = (HashMap<String, Object>) content
								.get(Constants.PROFILE_DETAILS);
						if (MapUtils.isNotEmpty(profileDetails)) {
							HashMap<String, Object> personalDetails = (HashMap<String, Object>) profileDetails
									.get(Constants.PERSONAL_DETAILS);
							if (MapUtils.isNotEmpty(personalDetails)) {
								mdoResults.add((String) personalDetails.get(Constants.PRIMARY_EMAIL));
							}
						}
					}
				}
			}
		} catch (Exception e) {
			logger.error("Exception while fetching user setails : ",e);
			throw new ApplicationException("Hub Service ERROR: ", e);
		}
		return mdoResults;
	}

	public boolean validateJsonAgainstSchema(Map<String, Object> existingProfileDetails) {
		try {
			String jsonData = new Gson().toJson(existingProfileDetails);
			String jsonSchema = getVerifiedProfileSchema();
			JSONObject rawSchema = new JSONObject(new JSONTokener(jsonSchema));
			JSONObject data = new JSONObject(new JSONTokener(jsonData));
			Schema schema = SchemaLoader.load(rawSchema);
			schema.validate(data);
		} catch (JSONException e) {
			throw new RuntimeException("Can't parse json schema: " + e.getMessage(), e);
		} catch (ValidationException ex) {
			StringBuffer result = new StringBuffer("Validation against Json schema failed: \n");
			ex.getAllMessages().stream().peek(e -> result.append("\n")).forEach(result::append);
			logger.info(String.format("Exception : %s", result.toString()));
			return false;
		}
		return true;
	}

	public String getVerifiedProfileSchema() {
		Map<String, String> header = new HashMap<>();
		Object data = requestServiceImpl
				.fetchResultUsingGet(new StringBuilder(configuration.getLmsServiceHost() + configuration.getVerifiedProfileFieldsPath()));
		if (null == data) {
			return null;
		}
		Map<String, Object> readResponse = mapper.convertValue(data, Map.class);
		Map<String, Object> result = (Map<String, Object>) readResponse.get(Constants.RESULT);
		Map<String, Object> response = (Map<String, Object>) result.get(Constants.RESPONSE);
		return (String) response.get(Constants.VALUE);
	}

	public void updateUserProfileForBulkUpload(WfRequest wfRequest) {
		this.updateProfile(wfRequest);
	}

	private Map<String, Object> deepCopyObject(Map<String, Object> oldObject) {
		try {
			ObjectMapper om = new ObjectMapper();
			String objStr = om.writeValueAsString(oldObject);
			return om.readValue(objStr, new TypeReference<Map<String, Object>>() {
			});
		}catch(Exception e) {
			logger.error("Failed to create a copy for Object. Exception: " + e.getMessage(), e);
		}
		return null;
	}

	public void updateUserProfileV2(List<WfRequest> wfRequests, String userId) {
		try {
			Map<String, Object> readData = (Map<String, Object>) userProfileRead(userId);
			if (readData != null && !Constants.OK.equals(readData.get(Constants.RESPONSE_CODE))) {
				Map<String, Object> params = (Map<String, Object>) readData.getOrDefault(Constants.PARAMS, Collections.emptyMap());
				String detailedError = (String) params.getOrDefault(Constants.ERROR_MESSAGE, "No additional details provided.");
				String errorMessage = "User not found: " + detailedError;
				logger.error(errorMessage);
				wfRequests.forEach(wfRequest -> failedCase(wfRequest, errorMessage));
				return;
			}
			Map<String, Object> existingUserResults = (Map<String, Object>) readData.get(Constants.RESULT);
			Map<String, Object> existingUserResponse = (Map<String, Object>) existingUserResults.get(Constants.RESPONSE);
			Map<String, Object> profileDetails = (Map<String, Object>) existingUserResponse.get(Constants.PROFILE_DETAILS);
			boolean isUpdateRequired = false;
			for (WfRequest wfRequest : wfRequests) {
				WfStatusEntity wfStatusEntity = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(),
						wfRequest.getWfId());
				if ((Constants.PROFILE_SERVICE_NAME.equals(wfRequest.getServiceName()) && Constants.APPROVED_STATE.equals(wfStatusEntity.getCurrentStatus()))
						|| (Constants.USER_PROFILE_FLAG_SERVICE.equals(wfRequest.getServiceName()) && Constants.PROCESSED_STATE.equals(wfStatusEntity.getCurrentStatus()))) {

					List<HashMap<String, Object>> updatedFieldValues = wfRequest.getUpdateFieldValues();
					HashMap<String, Object> updatedFieldValueElement = updatedFieldValues.get(0);
					HashMap<String, Object> toValueList = (HashMap<String, Object>) updatedFieldValueElement.get(Constants.TO_VALUE);
					for (String key : toValueList.keySet()) {
						if (Constants.NAME.equals(key) && StringUtils.isNotEmpty((String) toValueList.get(Constants.NAME))) {
							updateProfile(wfRequest);
						} else {
							Map<String, Object> updateRequest = updateRequestWithWF(wfRequest.getUserId(), wfRequest.getUpdateFieldValues(), profileDetails);
							isUpdateRequired = true;
							if (updateRequest == null) {
								logger.error("User profile data type error for request: {}", wfRequest.getWfId());
								failedCase(wfRequest, "User profile data type error.");
							}
						}
					}
				}
			}
			if (isUpdateRequired) {
				updateUserProfileData(userId, profileDetails, wfRequests, existingUserResponse);
			}
		} catch (Exception e) {
			logger.error("Exception occurred : ", e);
		}
	}

	private void updateUserProfileData(String userId, Map<String, Object> profileDetails, List<WfRequest> wfRequests, Map<String, Object> userDetails) {
		try {
			Map<String, Object> profileUpdateRequest = new HashMap<>();
			profileUpdateRequest.put(Constants.USER_ID, userId);
			profileUpdateRequest.put(Constants.PROFILE_DETAILS, profileDetails);

			Map<String, Object> profileUpdateRequestBody = new HashMap<>();
			profileUpdateRequestBody.put(Constants.REQUEST, profileUpdateRequest);

			Map<String, Object> updateUserApiResp = requestServiceImpl
					.fetchResultUsingPatch(configuration.getLmsServiceHost() + configuration.getUserProfileUpdateEndPoint(), profileUpdateRequestBody, getHeaders());

			if (updateUserApiResp == null || !Constants.OK.equals(updateUserApiResp.get(Constants.RESPONSE_CODE))) {
				Map<String, Object> params = (Map<String, Object>) updateUserApiResp.getOrDefault(Constants.PARAMS, Collections.emptyMap());
				String updateError = (String) params.getOrDefault(Constants.ERROR_MESSAGE, "Unknown error");
				String errorMessage = "User update failed: " + updateError;
				logger.error("User update failed: {}", updateError);
				failedCaseProfileUpdate(wfRequests, errorMessage);
			} else {
				String cacheKey = Constants.USER_BASIC_PROFILE_REDIS_KEY_PREFIX + userId;
				logger.info("User profile updated. Invalidating basicProfile cache for userId: {}", userId);
				redisCacheMgr.deleteCache(cacheKey);
			}
		} catch (Exception e) {
			logger.error("Error updating user profile for userId: {}", userId, e);
		}
	}

	private void handlePendingRequestUpdate(WfRequest wfRequest, String updatedDeptName) {
		try {
			Map<String, Object> request = new HashMap<>();
			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put(Constants.USER_ID, wfRequest.getApplicationId());
			requestBody.put(Constants.DEPARTMENT_NAME, updatedDeptName);
			request.put(Constants.REQUEST, requestBody);

			workflowService.updatePendingRequestsToNewMDO(request);
		} catch (Exception e) {
			logger.error("Error updating pending requests for wfRequest: {}", wfRequest.getApplicationId(), e);
		}
	}

	private void failedCase(WfRequest wfRequest, String errorMsg) {
		WfStatusEntity wfStatusEntity = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(),
				wfRequest.getWfId());
		wfRequest.setState(Constants.FAILED);
		wfRequest.setAction(Constants.FAILED);
		wfStatusEntity.setLastUpdatedOn(new Date());
		wfStatusEntity.setCurrentStatus(Constants.FAILED);
		wfStatusEntity.setInWorkflow(false);
		wfStatusEntity.setComment(errorMsg);
		wfStatusRepo.save(wfStatusEntity);
	}

	private void failedCaseProfileUpdate(List<WfRequest> wfRequests, String updateError) {

		for (WfRequest wfRequest : wfRequests) {
			List<HashMap<String, Object>> updatedFieldValues = wfRequest.getUpdateFieldValues();
			HashMap<String, Object> updatedFieldValueElement = updatedFieldValues.get(0);
			HashMap<String, Object> toValue = (HashMap<String, Object>) updatedFieldValueElement.get(Constants.TO_VALUE);
			Set<String> keySet = toValue.keySet();
			if (!keySet.contains(Constants.NAME)) {
				WfStatusEntity wfStatusEntity = wfStatusRepo.findByApplicationIdAndWfId(wfRequest.getApplicationId(),
						wfRequest.getWfId());
				wfRequest.setState(Constants.FAILED);
				wfRequest.setAction(Constants.FAILED);
				wfStatusEntity.setLastUpdatedOn(new Date());
				wfStatusEntity.setCurrentStatus(Constants.FAILED);
				wfStatusEntity.setInWorkflow(false);
				wfStatusEntity.setComment(updateError);
				wfStatusRepo.save(wfStatusEntity);
			}
		}
	}

    private Map<String, Object> fetchOrgDetails(String deptName) {
        logger.debug("Fetching Org details for deptName: {}", deptName);
        if (StringUtils.isNotEmpty(deptName)) {
            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put(Constants.FILTERS, Collections.singletonMap(Constants.CHANNEL, deptName));
            Map<String, Object> requestObj = new HashMap<>();
            requestObj.put(Constants.REQUEST, reqMap);
            HashMap<String, String> headersValue = new HashMap<>();
            headersValue.put(Constants.CONTENT_TYPE, Constants.APPLICATION_JSON);
            try {
                StringBuilder builder = new StringBuilder(configuration.getLmsServiceHost());
                builder.append(configuration.getLmsOrgSearchEndPoint());
                Map<String, Object> orgDetails = (Map<String, Object>) requestServiceImpl
                        .fetchResultUsingPost(builder, requestObj, Map.class, headersValue);
                if (MapUtils.isNotEmpty(orgDetails) && Constants.OK.equalsIgnoreCase((String)orgDetails.get(Constants.RESPONSE_CODE))) {
                    Map<String, Object> result = (Map<String, Object>) orgDetails.get(Constants.RESULT);
                    if (MapUtils.isNotEmpty(result) && result.get(Constants.RESPONSE) instanceof Map) {
                        Map<String, Object> response = (Map<String, Object>) result.get(Constants.RESPONSE);
                        if (MapUtils.isNotEmpty(response) && response.get(Constants.CONTENT) instanceof List) {
                            List<Map<String, Object>> contentList = (List<Map<String, Object>>) response.get(Constants.CONTENT);
                            if (CollectionUtils.isNotEmpty(contentList)) {
                                return contentList.get(0);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("There is a error occured while searching for the Org details : {}",e.getMessage());
            }
        }
        return Map.of();
    }
}
