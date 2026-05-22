package org.sunbird.workflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {

    Configuration config;

    @BeforeEach
    void setUp() {
        config = new Configuration();
    }

    @Test
    void testListAndSimpleFields() {
        config.setMdoAdminSearchFields("name,email");
        assertEquals(List.of("name", "email"), config.getMdoAdminSearchFields());

        config.setBpBatchFullValidationExcludeStates("KA,MH");
        assertEquals(List.of("KA", "MH"), config.getBpBatchFullValidationExcludeStates());

        config.setBulkUploadGenderValue("male,female,other");
        assertEquals(List.of("male", "female", "other"), config.getBulkUploadGenderValue());

        config.setBulkUploadCategoryValue("sc,st,obc");
        assertEquals(List.of("sc", "st", "obc"), config.getBulkUploadCategoryValue());

        config.setGroupValues("group1,group2");
        assertEquals(List.of("group1", "group2"), config.getGroupValues());

        config.setPendingRequestCountLimit(50);
        assertEquals(50, config.getPendingRequestCountLimit());

        config.setBpBatchEnrolLimitBufferSize(200);
        assertEquals(200, config.getBpBatchEnrolLimitBufferSize());

        config.setLmsUserSearchLimit(150);
        assertEquals(150, config.getLmsUserSearchLimit());
    }

    @Test
    void testUrlsAndServicePaths() {
        config.setAdminEnrolEndPoint("http://admin/enrol");
        assertEquals("http://admin/enrol", config.getAdminEnrolEndPoint());

        config.setContentReadSearchEndPoint("http://content/read");
        assertEquals("http://content/read", config.getContentReadEndPoint());

        config.setCourseServiceHost("http://courses");
        assertEquals("http://courses", config.getCourseServiceHost());

        config.setLmsServiceHost("http://lms");
        assertEquals("http://lms", config.getLmsServiceHost());

        config.setMultilevelBPEnrolEndPoint("/multilevel/enrol");
        assertEquals("/multilevel/enrol", config.getMultilevelBPEnrolEndPoint());

        config.setLmsOrgSearchEndPoint("/org/search");
        assertEquals("/org/search", config.getLmsOrgSearchEndPoint());

        config.setLmsUserSearchEndPoint("/lms/user/search");
        assertEquals("/lms/user/search", config.getLmsUserSearchEndPoint());

        config.setHubProfileSearchEndPoint("/hub/search");
        assertEquals("/hub/search", config.getHubProfileSearchEndPoint());

        config.setProfileServiceConfigPath("/profile/config");
        assertEquals("/profile/config", config.getProfileServiceConfigPath());

        config.setContentServiceHost("http://content.service");
        assertEquals("http://content.service", config.getContentServiceHost());

        config.setUserRegistrationServiceConfigPath("/user/register");
        assertEquals("/user/register", config.getUserRegistrationServiceConfigPath());

        config.setVerifiedProfileFieldsPath("/profile/verified");
        assertEquals("/profile/verified", config.getVerifiedProfileFieldsPath());

        config.setPositionServiceConfigPath("/position/config");
        assertEquals("/position/config", config.getPositionServiceConfigPath());

        config.setOrgServiceConfigPath("/org/config");
        assertEquals("/org/config", config.getOrgServiceConfigPath());

        config.setDomainServiceConfigPath("/domain/config");
        assertEquals("/domain/config", config.getDomainServiceConfigPath());

        config.setWorkflowCreateUserTopic("workflow.topic");
        assertEquals("workflow.topic", config.getWorkflowCreateUserTopic());

        config.setWorkflowApplicationTopicV2("workflow.app.v2");
        assertEquals("workflow.app.v2", config.getWorkflowApplicationTopicV2());

        config.setWorkFlowNotificationTopic("workflow.notify");
        assertEquals("workflow.notify", config.getWorkFlowNotificationTopic());

        config.setWorkFlowNotificationTopicV2("workflow.notify.v2");
        assertEquals("workflow.notify.v2", config.getWorkFlowNotificationTopicV2());

    }

    @Test
    void testWorkflowAndNotificationFields() {

        config.setSenderMail("admin@example.com");
        assertEquals("admin@example.com", config.getSenderMail());

        config.setDomainHost("domain.com");
        assertEquals("domain.com", config.getDomainHost());

        config.setUserBulkUpdateTopic("user.bulk.update");
        assertEquals("user.bulk.update", config.getUserBulkUpdateTopic());

        config.setUserBulkUpdateFolderName("bulk-folder");
        assertEquals("bulk-folder", config.getUserBulkUpdateFolderName());

        config.setBlendedProgramServicePath("/blended");
        assertEquals("/blended", config.getBlendedProgramServicePath());

        config.setAdminUnEnrolEndPoint("/admin/unenrol");
        assertEquals("/admin/unenrol", config.getAdminUnEnrolEndPoint());

        config.setCloudStorageKey("cloud-key");
        assertEquals("cloud-key", config.getCloudStorageKey());

        config.setCloudStorageSecret("cloud-secret");
        assertEquals("cloud-secret", config.getCloudStorageSecret());

        config.setCloudStorageEndpoint("https://cloud.endpoint");
        assertEquals("https://cloud.endpoint", config.getCloudStorageEndpoint());

        config.setWorkflowCloudContainerName("wf-container");
        assertEquals("wf-container", config.getWorkflowCloudContainerName());

        config.setDomainValidationRegex(".*\\.gov\\.in");
        assertEquals(".*\\.gov\\.in", config.getDomainValidationRegex());

        config.setNotificationEmailTemplate("notify-template");
        assertEquals("notify-template", config.getNotificationEmailTemplate());

        config.setLearnerForwardedMailBody("Forwarded");
        assertEquals("Forwarded", config.getLearnerForwardedMailBody());

        config.setRequestForwardedMailBody("ForwardedRequest");
        assertEquals("ForwardedRequest", config.getRequestForwardedMailBody());

        config.setApprovalRequetMailBody("Approval");
        assertEquals("Approval", config.getApprovalRequetMailBody());

        config.setRejectedOrRemovedMailBody("Rejected");
        assertEquals("Rejected", config.getRejectedOrRemovedMailBody());

        config.setApprovedMailBody("Approved");
        assertEquals("Approved", config.getApprovedMailBody());

        config.setNominationRequestMailBody("Nominate");
        assertEquals("Nominate", config.getNominationRequestMailBody());

        config.setEnvHierarchyKeystoreKeyspaceName("env-keyspace");
        assertEquals("env-keyspace", config.getEnvHierarchyKeystoreKeyspaceName());

        config.setCloudStorageTypeName("azure");
        assertEquals("azure", config.getCloudStorageTypeName());

        config.setMdoEmailTemplate("mdo-template");
        assertEquals("mdo-template", config.getMdoEmailTemplate());

        config.setMdoBaseUrl("http://mdo.base");
        assertEquals("http://mdo.base", config.getMdoBaseUrl());

        config.setOrgChangeNotificationTemplate("org-change");
        assertEquals("org-change", config.getOrgChangeNotificationTemplate());

        config.setBpAprroveAndRejectEmailTemplate("approve-reject-template");
        assertEquals("approve-reject-template", config.getBpAprroveAndRejectEmailTemplate());
    }


    @Test
    void testStringAndBooleanConfigProperties() {
        // Set string values
        config.setAdminBlendedProgramEnrolEndPoint("adminEnrol");
        config.setModificationRecordAllowActions("modify,delete");
        config.setBatchFullMesg("Batch is full");
        config.setConflictRejectReason("Conflict occurred");
        config.setMailBody("Mail content");
        config.setHubServiceHost("http://hub-service");
        config.setUserProfileReadEndPoint("/user/read");
        config.setUserProfileUpdateEndPoint("/user/update");
        config.setUserProfileMigrateEndPoint("/user/migrate");
        config.setLmsAssignRoleEndPoint("/assign/role");
        config.setHubProfileUpdateEndPoint("/hub/profile/update");
        config.setPidServiceHost("http://pid-service");
        config.setMultipleSearchEndPoint("/search/multiple");
        config.setLexCoreServiceHost("http://lex-core");
        config.setUserRoleSearchEndpoint("/user/role/search");
        config.setWorkflowApplicationTopic("workflow-topic");
        config.setNotifyServiceHost("http://notify-service");
        config.setNotifyServicePath("/notify");
        config.setHubRootOrg("root-org-id");
        config.setDepartmentUpdatePath("/department/update");
        config.setMultipleWfCreationEnable(true);

        // Validate string getters
        assertEquals("adminEnrol", config.getAdminBlendedProgramEnrolEndPoint());
        assertEquals("modify,delete", config.getModificationRecordAllowActions());
        assertEquals("Batch is full", config.getBatchFullMesg());
        assertEquals("Conflict occurred", config.getConflictRejectReason());
        assertEquals("Mail content", config.getMailBody());
        assertEquals("http://hub-service", config.getHubServiceHost());
        assertEquals("/user/read", config.getUserProfileReadEndPoint());
        assertEquals("/user/update", config.getUserProfileUpdateEndPoint());
        assertEquals("/user/migrate", config.getUserProfileMigrateEndPoint());
        assertEquals("/assign/role", config.getLmsAssignRoleEndPoint());
        assertEquals("/hub/profile/update", config.getHubProfileUpdateEndPoint());
        assertEquals("http://pid-service", config.getPidServiceHost());
        assertEquals("/search/multiple", config.getMultipleSearchEndPoint());
        assertEquals("http://lex-core", config.getLexCoreServiceHost());
        assertEquals("/user/role/search", config.getUserRoleSearchEndpoint());
        assertEquals("workflow-topic", config.getWorkflowApplicationTopic());
        assertEquals("http://notify-service", config.getNotifyServiceHost());
        assertEquals("/notify", config.getNotifyServicePath());
        assertEquals("root-org-id", config.getHubRootOrg());
        assertEquals("/department/update", config.getDepartmentUpdatePath());

        // Validate boolean getters
        assertTrue(config.getMultipleWfCreationEnable());
        assertTrue(config.isMultipleWfCreationEnable());
    }

    @Test
    void testIntegerConfigProperties() {
        // Set integer values
        config.setEnrolStatusCountLocalCacheSize(100);
        config.setEnrolStatusCountLocalTimeToLive(3600);
        config.setDefaultLimit(20);
        config.setDefaultOffset(0);
        config.setMaxLimit(100);

        // Validate integer getters
        assertEquals(100, config.getEnrolStatusCountLocalCacheSize());
        assertEquals(3600, config.getEnrolStatusCountLocalTimeToLive());
        assertEquals(20, config.getDefaultLimit());
        assertEquals(0, config.getDefaultOffset());
        assertEquals(100, config.getMaxLimit());
    }

    @Test
    void testTimezoneConfiguration() {
        config.setSunbirdTimeZone("Asia/Kolkata");
        assertEquals("Asia/Kolkata", config.getSunbirdTimeZone());

        // Test with different timezone
        config.setSunbirdTimeZone("America/New_York");
        assertEquals("America/New_York", config.getSunbirdTimeZone());

        // Test with UTC
        config.setSunbirdTimeZone("UTC");
        assertEquals("UTC", config.getSunbirdTimeZone());
    }

}
