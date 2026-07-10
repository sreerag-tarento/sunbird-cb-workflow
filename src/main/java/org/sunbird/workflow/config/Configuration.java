package org.sunbird.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class Configuration {

    @Value("${workflow.pagination.default.limit}")
    private Integer defaultLimit;

    @Value("${workflow.pagination.default.offset}")
    private Integer defaultOffset;

    @Value("${workflow.pagination.max.limit}")
    private Integer maxLimit;

    @Value("${hub.service.host}")
    private String hubServiceHost;

    @Value("${hub.profile.update}")
    private String hubProfileUpdateEndPoint;

    @Value("${lms.user.read}")
    private String userProfileReadEndPoint;

    @Value("${lms.user.update}")
    private String userProfileUpdateEndPoint;

    @Value("${lms.user.migrate}")
    private String userProfileMigrateEndPoint;

    @Value("${lms.service.host}")
    private String lmsServiceHost;

    @Value("${lms.user.search}")
    private String lmsUserSearchEndPoint;

    @Value("${lms.org.search.path}")
    private String lmsOrgSearchEndPoint;

    @Value("${lms.assign.role}")
    private String lmsAssignRoleEndPoint;

    @Value("${pid.service.host}")
    private String pidServiceHost;

    @Value("${pid.multiplesearch.endpoint}")
    private String multipleSearchEndPoint;

    @Value("${lexcore.service.host}")
    private String lexCoreServiceHost;

    @Value("${userrole.search.endpoint}")
    private String userRoleSearchEndpoint;

    @Value("${kafka.topics.workflow.request}")
    private String workflowApplicationTopic;

    @Value("${multiple-workflow-creation}")
    private boolean multipleWfCreationEnable;

    @Value("${notify.service.host}")
    private String notifyServiceHost;

    @Value("${notify.service.path}")
    private String notifyServicePath;

    @Value("${hub.notification.rootOrg}")
    private String hubRootOrg;

    @Value("${portal.departmentupdate.path}")
    private String departmentUpdatePath;

    @Value("${hub.profile.search}")
    private String hubProfileSearchEndPoint;

    @Value("${kafka.topics.workflow.notification}")
    private String workFlowNotificationTopic;
    @Value("${kafka.topics.user.registration.createUser}")
    private String workflowCreateUserTopic;

    @Value("${lms.system.settings.wfProfileService.path}")
    private String profileServiceConfigPath;

    @Value("${lms.system.settings.wfPositionService.path}")
    private String positionServiceConfigPath;

    @Value("${lms.system.settings.wfOrgService.path}")
    private String orgServiceConfigPath;

    @Value("${lms.system.settings.wfDomainService.path}")
    private String domainServiceConfigPath;

    @Value("${notification.sender.mail}")
    private String senderMail;

    @Value("${mdo.search.fields}")
    private String mdoAdminSearchFields;

    @Value("${mdo.approval.base.url}")
    private String mdoBaseUrl;

    @Value("${domain.host}")
    private String domainHost;

    @Value("${mdo.email.template}")
    private String mdoEmailTemplate;

    @Value("${notification.email.body}")
    private String mailBody;

    @Value("${lms.system.settings.wfUserRegistrationService.path}")
    private String userRegistrationServiceConfigPath;

    @Value("${lms.system.settings.verified.profile.fields.path}")
    private String verifiedProfileFieldsPath;

    @Value("${lms.system.settings.wfBlendedProgramService.path}")
    private String blendedProgramServicePath;

    @Value("${course.service.host}")
    private String courseServiceHost;

    @Value("${course.admin.enrol}")
    private String adminEnrolEndPoint;

    @Value("${bp.email.template}")
    private String bpAprroveAndRejectEmailTemplate;

    @Value("${bp.batch.full.validation.exclude.states}")
    private String bpBatchFullValidationExcludeStates;

    @Value("${bp.batch.enrol.limit.buffer.size}")
    private Integer bpBatchEnrolLimitBufferSize;

    @Value("${course.admin.unenrol}")
    private String adminUnEnrolEndPoint;

    @Value("${content.service.host}")
    private String contentServiceHost;

    @Value("${content.read}")
    private String contentReadEndPoint;

    @Value("${ms.system.settings.multilevelBPEnroll.path}")
    private String multilevelBPEnrolEndPoint;

    @Value("${enrol.status.count.local.cache.size}")
    private Integer enrolStatusCountLocalCacheSize;

    @Value("${blended.program.enrol.conflict.reject.reason}")
    private String conflictRejectReason;

    @Value("${blended.program.enrol.batch.full.message}")
    private String batchFullMesg;

    @Value("${wfstatus.allowed.action.for.modification.history.entry}")
    private String modificationRecordAllowActions;

    @Value("${blended.program.batch.in.progress.message}")
    private String batchInProgressMessage;

    @Value("${bp.mail.body.forwarded.to}")
    private String learnerForwardedMailBody;

    @Value("${bp.mail.body.rejected.or.remove}")
    private String rejectedOrRemovedMailBody;

    @Value("${bp.mail.body.approve}")
    private String approvedMailBody;

    @Value("${notify.email.template}")
    private String notificationEmailTemplate;

    @Value("${bp.approval.request.mail.body}")
    private String approvalRequetMailBody;

    @Value("${bp.request.forwarded.mail.body}")
    private String requestForwardedMailBody;

    @Value("${bp.nomination.request.mail.body}")
    private String nominationRequestMailBody;

    @Value("${domain.validation.regex}")
    private String domainValidationRegex;

    @Value("${env.hierarchy.keystore.keyspace}")
    private String envHierarchyKeystoreKeyspaceName;

    @Value("${cloud.storage.type.name}")
    private String cloudStorageTypeName;

    @Value("${cloud.storage.key}")
    private String cloudStorageKey;

    @Value("${cloud.storage.secret}")
    private String cloudStorageSecret;

    @Value("${cloud.storage.endpoint}")
    private String cloudStorageEndpoint;

    @Value("${workflow.cloud.container.name}")
    private String workflowCloudContainerName;

    @Value("${user.bulk.update.folder.name}")
    private String userBulkUpdateFolderName;

    @Value("${kafka.topics.user.bulk.update}")
    private String userBulkUpdateTopic;

    @Value("${mdo.org.change.email.template}")
    private String orgChangeNotificationTemplate;

    @Value("${user.bulk.upload.gender.value}")
    private String bulkUploadGenderValue;

    @Value("${user.bulk.upload.category.value}")
    private String bulkUploadCategoryValue;

    @Value("${user.default.group.values}")
    private String groupValues;

    @Value("${pending.request.count.limit}")
    private int pendingRequestCountLimit;

    @Value("${sb.csv.delimiter}")
    private char csvDelimiter;

    @Value(("${sb.tags.delimiter}"))
    private String tagsDelimiter;

    @Value("${course.admin.blended.program.enrol}")
    private String adminBlendedProgramEnrolEndPoint;

    @Value("${kafka.topics.workflow.notification.v2}")
    private String workFlowNotificationTopicV2;

    @Value("${kafka.topics.workflow.request.v2}")
    private String workflowApplicationTopicV2;

    @Value("${lms.service.user.search.limit}")
    private Integer lmsUserSearchLimit;

    @Value("${kafka.topics.community.moderator.transfer}")
    private String communityModeratorTransferTopic;

    @Value("${notification.template.community.moderator.transfer}")
    private String communityModeratorTransferTemplate;

    @Value("${community.read.endpoint}")
    private String communityReadEndpoint;

    @Value("${community.service.base.url}")
    private String communityServiceBaseUrl;

    @Value("${kafka.topic.transfer.request.status.change}")
    private String transferRequestStatusChangeTopic;

    @Value("${cb.pores.service.host}")
    private String cbPoresServiceHost;

    @Value("${cb.pores.master.designation.endpoint}")
    private String cbPoresMasterDesignationEndpoint;

    @Value("${designation.search.result.size}")
    private int searchDesignationResultSize;

    @Value("${user.profile.cache.ttl}")
    private int basicProfileCacheTtl;

    @Value("${ai.assessment.topic}")
    private String aiAssessmentTopic;

    @Value("${lms.service.config.path}")
    private String lmsServiceConfigPath;

    @Value("${ai.assessment.initiate.roles}")
    private String aiAssessmentInitiateRoles;

    @Value("${ai.assessment.approve.reject.roles}")
    private String aiAssessmentApproveRejectRoles;

    @Value("${ai.assessment.spv.email.subject}")
    private String aiAssessmentSpvEmailSubject;

    @Value("${ai.assessment.spv.email.body}")
    private String aiAssessmentSpvEmailBody;

    @Value("${ai.assessment.approved.email.subject}")
    private String aiAssessmentApprovedEmailSubject;

    @Value("${ai.assessment.approved.email.body}")
    private String aiAssessmentApprovedEmailBody;

    @Value("${ai.assessment.rejected.email.subject}")
    private String aiAssessmentRejectedEmailSubject;

    @Value("${ai.assessment.rejected.email.body}")
    private String aiAssessmentRejectedEmailBody;

    @Value("${ai.assessment.approval.link}")
    private String aiAssessmentApprovalLink;

    @Value("${cbp.portal.host}")
    private String cbpPortalHost;

    @Value("${ai.assessment.spv.admin.email}")
    private List<String> aiAssessmentSpvAdminEmail;

    @Value("${sunbird_time_zone}")
    private String sunbirdTimeZone;

    @Value("${kafka.topics.bp.batch.stats}")
    private String bpBatchStatsTopic;

    @Value("${bp.batch.stats.cache.ttl}")
    private int bpBatchStatsCacheTtl;

    @Value("${bp.batch.stats.cache.index}")
    private int bpBatchStatsCacheIndex;

    @Value("${bp.batch.stats.sync.db.chunk.size:50}")
    private int bpBatchStatsSyncDbChunkSize;

    @Value("${sb.search.service.host}")
    private String sbSearchServiceHost;

    @Value("${sb.composite.v4.search}")
    private String sbCompositeV4Search;

    @Value("${bp.batch.stats.sync.redis.pipeline.size:100}")
    private int bpBatchStatsSyncRedisPipelineSize;

    @Value("${bp.batch.stats.sync.page.size:100}")
    private int bpBatchStatsSyncPageSize;

    public String getAdminBlendedProgramEnrolEndPoint() {
        return adminBlendedProgramEnrolEndPoint;
    }

    public void setAdminBlendedProgramEnrolEndPoint(String adminBlendedProgramEnrolEndPoint) {
        this.adminBlendedProgramEnrolEndPoint = adminBlendedProgramEnrolEndPoint;
    }

    public String getModificationRecordAllowActions() {
        return modificationRecordAllowActions;
    }

    public void setModificationRecordAllowActions(String modificationRecordAllowActions) {
        this.modificationRecordAllowActions = modificationRecordAllowActions;
    }

    public String getBatchFullMesg() {
        return batchFullMesg;
    }

    public void setBatchFullMesg(String batchFullMesg) {
        this.batchFullMesg = batchFullMesg;
    }

    public String getConflictRejectReason() {
        return conflictRejectReason;
    }

    public void setConflictRejectReason(String conflictRejectReason) {
        this.conflictRejectReason = conflictRejectReason;
    }

    public Integer getEnrolStatusCountLocalCacheSize() {
        return enrolStatusCountLocalCacheSize;
    }

    public void setEnrolStatusCountLocalCacheSize(Integer enrolStatusCountLocalCacheSize) {
        this.enrolStatusCountLocalCacheSize = enrolStatusCountLocalCacheSize;
    }

    public Integer getEnrolStatusCountLocalTimeToLive() {
        return enrolStatusCountLocalTimeToLive;
    }

    public void setEnrolStatusCountLocalTimeToLive(Integer enrolStatusCountLocalTimeToLive) {
        this.enrolStatusCountLocalTimeToLive = enrolStatusCountLocalTimeToLive;
    }

    @Value("${enrol.status.count.local.cache.timetolive}")
    private Integer enrolStatusCountLocalTimeToLive;


    public Integer getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(Integer defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public Integer getDefaultOffset() {
        return defaultOffset;
    }

    public void setDefaultOffset(Integer defaultOffset) {
        this.defaultOffset = defaultOffset;
    }

    public Integer getMaxLimit() {
        return maxLimit;
    }
    public String getMailBody() {
        return mailBody;
    }

    public void setMailBody(String mailBody) {
        this.mailBody = mailBody;
    }
    public void setMaxLimit(Integer maxLimit) {
        this.maxLimit = maxLimit;
    }

    public String getHubServiceHost() {
        return hubServiceHost;
    }

    public void setHubServiceHost(String hubServiceHost) {
        this.hubServiceHost = hubServiceHost;
    }

    public String getUserProfileReadEndPoint() {
        return userProfileReadEndPoint;
    }

    public void setUserProfileReadEndPoint(String userProfileReadEndPoint) {
        this.userProfileReadEndPoint = userProfileReadEndPoint;
    }

    public String getUserProfileUpdateEndPoint() {
        return userProfileUpdateEndPoint;
    }

    public void setUserProfileUpdateEndPoint(String userProfileUpdateEndPoint) {
        this.userProfileUpdateEndPoint = userProfileUpdateEndPoint;
    }

    public String getUserProfileMigrateEndPoint() {
        return userProfileMigrateEndPoint;
    }

    public void setUserProfileMigrateEndPoint(String userProfileMigrateEndPoint) {
        this.userProfileMigrateEndPoint = userProfileMigrateEndPoint;
    }

    public String getLmsAssignRoleEndPoint() {
        return lmsAssignRoleEndPoint;
    }

    public void setLmsAssignRoleEndPoint(String lmsAssignRoleEndPoint) {
        this.lmsAssignRoleEndPoint = lmsAssignRoleEndPoint;
    }

    public String getHubProfileUpdateEndPoint() {
        return hubProfileUpdateEndPoint;
    }

    public void setHubProfileUpdateEndPoint(String hubProfileUpdateEndPoint) {
        this.hubProfileUpdateEndPoint = hubProfileUpdateEndPoint;
    }

    public String getPidServiceHost() {
        return pidServiceHost;
    }

    public void setPidServiceHost(String pidServiceHost) {
        this.pidServiceHost = pidServiceHost;
    }

    public String getMultipleSearchEndPoint() {
        return multipleSearchEndPoint;
    }

    public void setMultipleSearchEndPoint(String multipleSearchEndPoint) {
        this.multipleSearchEndPoint = multipleSearchEndPoint;
    }

    public String getLexCoreServiceHost() {
        return lexCoreServiceHost;
    }

    public void setLexCoreServiceHost(String lexCoreServiceHost) {
        this.lexCoreServiceHost = lexCoreServiceHost;
    }

    public String getUserRoleSearchEndpoint() {
        return userRoleSearchEndpoint;
    }

    public void setUserRoleSearchEndpoint(String userRoleSearchEndpoint) {
        this.userRoleSearchEndpoint = userRoleSearchEndpoint;
    }

    public String getWorkflowApplicationTopic() {
        return workflowApplicationTopic;
    }

    public void setWorkflowApplicationTopic(String workflowApplicationTopic) {
        this.workflowApplicationTopic = workflowApplicationTopic;
    }

    public boolean getMultipleWfCreationEnable() {
        return multipleWfCreationEnable;
    }

    public void setMultipleWfCreationEnable(boolean multipleWfCreationEnable) {
        this.multipleWfCreationEnable = multipleWfCreationEnable;
    }

    public boolean isMultipleWfCreationEnable() {
        return multipleWfCreationEnable;
    }

    public String getNotifyServiceHost() {
        return notifyServiceHost;
    }

    public void setNotifyServiceHost(String notifyServiceHost) {
        this.notifyServiceHost = notifyServiceHost;
    }

    public String getNotifyServicePath() {
        return notifyServicePath;
    }

    public void setNotifyServicePath(String notifyServicePath) {
        this.notifyServicePath = notifyServicePath;
    }

    public String getHubRootOrg() {
        return hubRootOrg;
    }

    public void setHubRootOrg(String hubRootOrg) {
        this.hubRootOrg = hubRootOrg;
    }

    public String getDepartmentUpdatePath() {
        return departmentUpdatePath;
    }

    public void setDepartmentUpdatePath(String departmentUpdatePath) {
        this.departmentUpdatePath = departmentUpdatePath;
    }

    public String getHubProfileSearchEndPoint() {
        return hubProfileSearchEndPoint;
    }

    public void setHubProfileSearchEndPoint(String hubProfileSearchEndPoint) {
        this.hubProfileSearchEndPoint = hubProfileSearchEndPoint;
    }

    public String getWorkFlowNotificationTopic() {
        return workFlowNotificationTopic;
    }

    public void setWorkFlowNotificationTopic(String workFlowNotificationTopic) {
        this.workFlowNotificationTopic = workFlowNotificationTopic;
    }

    public String getLmsServiceHost() {
        return lmsServiceHost;
    }

    public void setLmsServiceHost(String lmsServiceHost) {
        this.lmsServiceHost = lmsServiceHost;
    }

    public String getLmsUserSearchEndPoint() {
        return lmsUserSearchEndPoint;
    }

    public void setLmsUserSearchEndPoint(String lmsUserSearchEndPoint) {
        this.lmsUserSearchEndPoint = lmsUserSearchEndPoint;
    }

    public String getWorkflowCreateUserTopic() {
        return workflowCreateUserTopic;
    }

    public void setWorkflowCreateUserTopic(String workflowCreateUserTopic) {
        this.workflowCreateUserTopic = workflowCreateUserTopic;
    }

    public String getProfileServiceConfigPath() {
        return profileServiceConfigPath;
    }

    public void setProfileServiceConfigPath(String profileServiceConfigPath) {
        this.profileServiceConfigPath = profileServiceConfigPath;
    }

    public String getPositionServiceConfigPath() {
        return positionServiceConfigPath;
    }

    public void setPositionServiceConfigPath(String positionServiceConfigPath) {
        this.positionServiceConfigPath = positionServiceConfigPath;
    }

    public String getOrgServiceConfigPath() {
        return orgServiceConfigPath;
    }

    public void setOrgServiceConfigPath(String orgServiceConfigPath) {
        this.orgServiceConfigPath = orgServiceConfigPath;
    }

    public String getDomainServiceConfigPath() {
        return domainServiceConfigPath;
    }

    public void setDomainServiceConfigPath(String domainServiceConfigPath) {
        this.domainServiceConfigPath = domainServiceConfigPath;
    }

    public String getSenderMail() {
        return senderMail;
    }

    public void setSenderMail(String senderMail) {
        this.senderMail = senderMail;
    }

    public List<String> getMdoAdminSearchFields() {
        return Arrays.asList(mdoAdminSearchFields.split(",", -1));
    }

    public void setMdoAdminSearchFields(String getMdoAdminSearchFields) {
        this.mdoAdminSearchFields = getMdoAdminSearchFields;
    }

    public String getMdoBaseUrl() {
        return mdoBaseUrl;
    }

    public void setMdoBaseUrl(String mdoBaseUrl) {
        this.mdoBaseUrl = mdoBaseUrl;
    }

    public String getDomainHost() {
        return domainHost;
    }

    public void setDomainHost(String domainHost) {
        this.domainHost = domainHost;
    }

    public String getMdoEmailTemplate() {
        return mdoEmailTemplate;
    }

    public void setMdoEmailTemplate(String mdoEmailTemplate) {
        this.mdoEmailTemplate = mdoEmailTemplate;
    }

    public String getUserRegistrationServiceConfigPath() {
        return userRegistrationServiceConfigPath;
    }

    public void setUserRegistrationServiceConfigPath(String userRegistrationServiceConfigPath) {
        this.userRegistrationServiceConfigPath = userRegistrationServiceConfigPath;
    }


    public String getVerifiedProfileFieldsPath() {
        return verifiedProfileFieldsPath;
    }

    public void setVerifiedProfileFieldsPath(String verifiedProfileFieldsPath) {
        this.verifiedProfileFieldsPath = verifiedProfileFieldsPath;
    }

    public String getBlendedProgramServicePath() {
        return blendedProgramServicePath;
    }

    public void setBlendedProgramServicePath(String blendedProgramServicePath) {
        this.blendedProgramServicePath = blendedProgramServicePath;
    }

    public String getCourseServiceHost() {
        return courseServiceHost;
    }

    public void setCourseServiceHost(String courseServiceHost) {
        this.courseServiceHost = courseServiceHost;
    }

    public String getAdminEnrolEndPoint() {
        return adminEnrolEndPoint;
    }

    public void setAdminEnrolEndPoint(String adminEnrolEndPoint) {
        this.adminEnrolEndPoint = adminEnrolEndPoint;
    }

    public String getBpAprroveAndRejectEmailTemplate() {
        return bpAprroveAndRejectEmailTemplate;
    }

    public void setBpAprroveAndRejectEmailTemplate(String bpAprroveAndRejectEmailTemplate) {
        this.bpAprroveAndRejectEmailTemplate = bpAprroveAndRejectEmailTemplate;
    }

    public List<String> getBpBatchFullValidationExcludeStates() {
        return Arrays.asList(bpBatchFullValidationExcludeStates.split(",", -1));
    }

    public void setBpBatchFullValidationExcludeStates(String bpBatchFullValidationExcludeStates) {
        this.bpBatchFullValidationExcludeStates = bpBatchFullValidationExcludeStates;
    }

    public Integer getBpBatchEnrolLimitBufferSize() {
        return bpBatchEnrolLimitBufferSize;
    }

    public void setBpBatchEnrolLimitBufferSize(Integer bpBatchEnrolLimitBufferSize) {
        this.bpBatchEnrolLimitBufferSize = bpBatchEnrolLimitBufferSize;
    }

    public String getAdminUnEnrolEndPoint() {
        return adminUnEnrolEndPoint;
    }

    public void setAdminUnEnrolEndPoint(String adminUnEnrolEndPoint) {
        this.adminUnEnrolEndPoint = adminUnEnrolEndPoint;
    }

    public String getContentServiceHost() {
        return contentServiceHost;
    }

    public void setContentServiceHost(String contentServiceHost) {
        this.contentServiceHost = contentServiceHost;
    }

    public String getContentReadEndPoint() {
        return contentReadEndPoint;
    }

    public void setContentReadSearchEndPoint(String contentReadEndPoint) {
        this.contentReadEndPoint = contentReadEndPoint;
    }

    public String getMultilevelBPEnrolEndPoint() {
        return multilevelBPEnrolEndPoint;
    }

    public void setMultilevelBPEnrolEndPoint(String multilevelBPEnrolEndPoint) {
        this.multilevelBPEnrolEndPoint = multilevelBPEnrolEndPoint;
    }


    public String getLmsOrgSearchEndPoint() {
        return lmsOrgSearchEndPoint;
    }

    public void setLmsOrgSearchEndPoint(String lmsOrgSearchEndPoint) {
        this.lmsOrgSearchEndPoint = lmsOrgSearchEndPoint;
    }


    public String getBatchInProgressMessage() {
        return batchInProgressMessage;
    }

    public void setBatchInProgressMessage(String batchInProgressMessage) {
        this.batchInProgressMessage = batchInProgressMessage;
    }

    public String getLearnerForwardedMailBody() {
        return learnerForwardedMailBody;
    }

    public void setLearnerForwardedMailBody(String learnerForwardedMailBody) {
        this.learnerForwardedMailBody = learnerForwardedMailBody;
    }

    public String getRejectedOrRemovedMailBody() {
        return rejectedOrRemovedMailBody;
    }

    public void setRejectedOrRemovedMailBody(String rejectedOrRemovedMailBody) {
        this.rejectedOrRemovedMailBody = rejectedOrRemovedMailBody;
    }

    public String getApprovedMailBody() {
        return approvedMailBody;
    }

    public void setApprovedMailBody(String approvedMailBody) {
        this.approvedMailBody = approvedMailBody;
    }

    public String getNotificationEmailTemplate() {
        return notificationEmailTemplate;
    }

    public void setNotificationEmailTemplate(String notificationEmailTemplate) {
        this.notificationEmailTemplate = notificationEmailTemplate;
    }

    public String getApprovalRequetMailBody() {
        return approvalRequetMailBody;
    }

    public void setApprovalRequetMailBody(String approvalRequetMailBody) {
        this.approvalRequetMailBody = approvalRequetMailBody;
    }

    public String getRequestForwardedMailBody() {
        return requestForwardedMailBody;
    }

    public void setRequestForwardedMailBody(String requestForwardedMailBody) {
        this.requestForwardedMailBody = requestForwardedMailBody;
    }

    public String getNominationRequestMailBody() {
        return nominationRequestMailBody;
    }

    public void setNominationRequestMailBody(String nominationRequestMailBody) {
        this.nominationRequestMailBody = nominationRequestMailBody;
    }
    public String getDomainValidationRegex() {
        return domainValidationRegex;
    }

    public void setDomainValidationRegex(String domainValidationRegex) {
        this.domainValidationRegex = domainValidationRegex;
    }

    public String getEnvHierarchyKeystoreKeyspaceName() {
        return envHierarchyKeystoreKeyspaceName;
    }

    public void setEnvHierarchyKeystoreKeyspaceName(String envHierarchyKeystoreKeyspaceName) {
        this.envHierarchyKeystoreKeyspaceName = envHierarchyKeystoreKeyspaceName;
    }

    public String getCloudStorageTypeName() {
        return cloudStorageTypeName;
    }

    public void setCloudStorageTypeName(String cloudStorageTypeName) {
        this.cloudStorageTypeName = cloudStorageTypeName;
    }

    public String getCloudStorageKey() {
        return cloudStorageKey;
    }

    public void setCloudStorageKey(String cloudStorageKey) {
        this.cloudStorageKey = cloudStorageKey;
    }

    public String getCloudStorageSecret() {
        return cloudStorageSecret;
    }

    public void setCloudStorageSecret(String cloudStorageSecret) {
        this.cloudStorageSecret = cloudStorageSecret;
    }

    public String getCloudStorageEndpoint() {
        return cloudStorageEndpoint;
    }

    public void setCloudStorageEndpoint(String cloudStorageEndpoint) {
        this.cloudStorageEndpoint = cloudStorageEndpoint;
    }

    public String getWorkflowCloudContainerName() {
        return workflowCloudContainerName;
    }

    public void setWorkflowCloudContainerName(String workflowCloudContainerName) {
        this.workflowCloudContainerName = workflowCloudContainerName;
    }

    public String getUserBulkUpdateFolderName() {
        return userBulkUpdateFolderName;
    }

    public void setUserBulkUpdateFolderName(String userBulkUpdateFolderName) {
        this.userBulkUpdateFolderName = userBulkUpdateFolderName;
    }

    public String getUserBulkUpdateTopic() {
        return userBulkUpdateTopic;
    }

    public void setUserBulkUpdateTopic(String userBulkUpdateTopic) {
        this.userBulkUpdateTopic = userBulkUpdateTopic;
    }

    public String getOrgChangeNotificationTemplate() {
        return orgChangeNotificationTemplate;
    }

    public void setOrgChangeNotificationTemplate(String orgChangeNotificationTemplate) {
        this.orgChangeNotificationTemplate = orgChangeNotificationTemplate;
    }

    public List<String> getBulkUploadGenderValue() {
        return Arrays.asList(bulkUploadGenderValue.split(",", -1));
    }

    public void setBulkUploadGenderValue(String bulkUploadGenderValue) {
        this.bulkUploadGenderValue = bulkUploadGenderValue;
    }

    public List<String> getBulkUploadCategoryValue() {
        return Arrays.asList(bulkUploadCategoryValue.split(",", -1));
    }

    public void setBulkUploadCategoryValue(String bulkUploadCategoryValue) {
        this.bulkUploadCategoryValue = bulkUploadCategoryValue;
    }

    public List<String> getGroupValues() {
        return Arrays.asList(this.groupValues.split(",", -1));
    }

    public void setGroupValues(String groupValues) {
        this.groupValues = groupValues;
    }

    public int getPendingRequestCountLimit() {
        return pendingRequestCountLimit;
    }

    public void setPendingRequestCountLimit(int pendingRequestCountLimit) {
        this.pendingRequestCountLimit = pendingRequestCountLimit;
    }
    public char getCsvDelimiter() {
        return csvDelimiter;
    }

    public String getTagsDelimiter() {
        return tagsDelimiter;
    }

    public String getWorkFlowNotificationTopicV2() {
        return workFlowNotificationTopicV2;
    }

    public void setWorkFlowNotificationTopicV2(String workFlowNotificationTopicV2) {
        this.workFlowNotificationTopicV2 = workFlowNotificationTopicV2;
    }

    public String getWorkflowApplicationTopicV2() {
        return workflowApplicationTopicV2;
    }

    public void setWorkflowApplicationTopicV2(String workflowApplicationTopicV2) {
        this.workflowApplicationTopicV2 = workflowApplicationTopicV2;
    }

    public Integer getLmsUserSearchLimit() { return lmsUserSearchLimit; }

    public void setLmsUserSearchLimit(Integer lmsUserSearchLimit) { this.lmsUserSearchLimit = lmsUserSearchLimit; }

    public String getCommunityModeratorTransferTopic() {
        return communityModeratorTransferTopic;
    }

    public String getCommunityModeratorTransferTemplate() {
        return communityModeratorTransferTemplate;
    }

    public String getCommunityReadEndpoint() {
        return communityReadEndpoint;
    }

    public String getCommunityServiceBaseUrl() {
        return communityServiceBaseUrl;
    }

    public String getTransferRequestStatusChangeTopic() {
        return transferRequestStatusChangeTopic;
    }

    public String getCbPoresServiceHost() { return cbPoresServiceHost; }

    public void setCbPoresServiceHost(String cbPoresServiceHost) { this.cbPoresServiceHost = cbPoresServiceHost; }

    public String getCbPoresMasterDesignationEndpoint() { return cbPoresMasterDesignationEndpoint; }

    public void setCbPoresMasterDesignationEndpoint(String cbPoresMasterDesignationEndpoint) { this.cbPoresMasterDesignationEndpoint = cbPoresMasterDesignationEndpoint; }

    public int getSearchDesignationResultSize() { return searchDesignationResultSize; }

    public void setSearchDesignationResultSize(int searchDesignationResultSize) { this.searchDesignationResultSize = searchDesignationResultSize; }

    public int getBasicProfileCacheTtl() { return basicProfileCacheTtl; }

    public void setBasicProfileCacheTtl(int basicProfileCacheTtl) { this.basicProfileCacheTtl = basicProfileCacheTtl; }

    public String getAiAssessmentTopic() {
        return aiAssessmentTopic;
    }

    public String getLmsServiceConfigPath() {
        return lmsServiceConfigPath;
    }

    public List<String> getAiAssessmentInitiateRoles() {
        return Arrays.asList(aiAssessmentInitiateRoles.split(","));
    }

    public List<String> getAiAssessmentApproveRejectRoles() {
        return Arrays.asList(aiAssessmentApproveRejectRoles.split(","));
    }

    public String getBpBatchStatsTopic() {
        return bpBatchStatsTopic;
    }

    public void setBpBatchStatsTopic(String bpBatchStatsTopic) {
        this.bpBatchStatsTopic = bpBatchStatsTopic;
    }

    public int getBpBatchStatsCacheTtl() {
        return bpBatchStatsCacheTtl;
    }

    public void setBpBatchStatsCacheTtl(int bpBatchStatsCacheTtl) {
        this.bpBatchStatsCacheTtl = bpBatchStatsCacheTtl;
    }

    public int getBpBatchStatsCacheIndex() {
        return bpBatchStatsCacheIndex;
    }

    public void setBpBatchStatsCacheIndex(int bpBatchStatsCacheIndex) {
        this.bpBatchStatsCacheIndex = bpBatchStatsCacheIndex;
    }

    public int getBpBatchStatsSyncDbChunkSize() {
        return bpBatchStatsSyncDbChunkSize;
    }

    public void setBpBatchStatsSyncDbChunkSize(int bpBatchStatsSyncDbChunkSize) {
        this.bpBatchStatsSyncDbChunkSize = bpBatchStatsSyncDbChunkSize;
    }

    public String getSbSearchServiceHost() {
        return sbSearchServiceHost;
    }

    public void setSbSearchServiceHost(String sbSearchServiceHost) {
        this.sbSearchServiceHost = sbSearchServiceHost;
    }

    public String getSbCompositeV4Search() {
        return sbCompositeV4Search;
    }

    public void setSbCompositeV4Search(String sbCompositeV4Search) {
        this.sbCompositeV4Search = sbCompositeV4Search;
    }

    public int getBpBatchStatsSyncRedisPipelineSize() {
        return bpBatchStatsSyncRedisPipelineSize;
    }

    public void setBpBatchStatsSyncRedisPipelineSize(int bpBatchStatsSyncRedisPipelineSize) {
        this.bpBatchStatsSyncRedisPipelineSize = bpBatchStatsSyncRedisPipelineSize;
    }

    public int getBpBatchStatsSyncPageSize() {
        return bpBatchStatsSyncPageSize;
    }

    public void setBpBatchStatsSyncPageSize(int bpBatchStatsSyncPageSize) {
        this.bpBatchStatsSyncPageSize = bpBatchStatsSyncPageSize;
    }

    public String getAiAssessmentSpvEmailSubject() {
        return aiAssessmentSpvEmailSubject;
    }

    public String getAiAssessmentSpvEmailBody() {
        return aiAssessmentSpvEmailBody;
    }

    public String getAiAssessmentApprovedEmailSubject() {
        return aiAssessmentApprovedEmailSubject;
    }

    public String getAiAssessmentApprovedEmailBody() {
        return aiAssessmentApprovedEmailBody;
    }

    public String getAiAssessmentRejectedEmailSubject() {
        return aiAssessmentRejectedEmailSubject;
    }

    public String getAiAssessmentRejectedEmailBody() {
        return aiAssessmentRejectedEmailBody;
    }

    public String getAiAssessmentApprovalLink() {
        return aiAssessmentApprovalLink;
    }

    public String getCbpPortalHost() {
        return cbpPortalHost;
    }

    public List<String> getAiAssessmentSpvAdminEmail() {
        return aiAssessmentSpvAdminEmail;
    }




    public String getSunbirdTimeZone() { return sunbirdTimeZone; }

    public void setSunbirdTimeZone(String sunbirdTimeZone) { this.sunbirdTimeZone = sunbirdTimeZone; }
}
