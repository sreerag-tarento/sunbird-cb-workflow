package org.sunbird.workflow.utils;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.SimpleQueryStringBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.sunbird.workflow.config.Constants;

@Service
public class ElasticsearchServiceManager {
    Logger logger = LogManager.getLogger(ElasticsearchServiceManager.class);

    @Value("${user_index_name}")
    private String userIndexName;

    @Autowired
    private RestHighLevelClient client;

    public static final String _DOC = "_doc";

    public boolean upsertWfRequest(String userId, List<String> wfRequests) {
        try {
            // Create or update the document
            Map<String, Object> updates = new HashMap<>();
            updates.put(Constants.WF_REQUESTS_KEY, wfRequests);

            UpdateRequest updateRequest = new UpdateRequest(userIndexName, _DOC, userId)
                    .doc(updates)
                    .upsert(new IndexRequest(userIndexName).id(userId).source(updates));

            client.update(updateRequest, RequestOptions.DEFAULT);
            logger.info("WfRequests Upsert successful for userId: {}", userId);
        } catch (Exception e) {
            logger.error("Failed to upsert wfRequests for userId: {}", userId, e);
            return false;
        }
        return true;
    }

    /**
     * Removes a specific wfRequest from the wfRequests list in user_alias index.
     */
    public boolean removeWfRequest(String userId, String wfRequestToRemove) {
        try {
            // Fetch the existing document
            GetResponse getResponse = client.get(new org.elasticsearch.action.get.GetRequest(userIndexName, _DOC, userId),
                    RequestOptions.DEFAULT);

            if (!getResponse.isExists()) {
                logger.error("Failed to find user information from ES for Id: {}", userId);
                return false;
            }

            Map<String, Object> source = getResponse.getSourceAsMap();
            List<String> wfRequests = (List<String>) source.get(Constants.WF_REQUESTS_KEY);

            if (wfRequests == null || !wfRequests.contains(wfRequestToRemove)) {
                logger.error("WFRequests object is empty or doesn't contain wf_id: {} for user: {}", wfRequestToRemove,
                        userId);
                return false;
            }

            // Remove the request
            wfRequests.remove(wfRequestToRemove);
            source.put(Constants.WF_REQUESTS_KEY, wfRequests);

            // Update document
            UpdateRequest updateRequest = new UpdateRequest(userIndexName, _DOC, userId).doc(source);
            client.update(updateRequest, RequestOptions.DEFAULT);

            logger.info("wfRequest with id: {} is removed successfully from user: {}", wfRequestToRemove, userId);
        } catch (IOException e) {
            logger.error("Failed to remove wf_id: {} from WFRequests for user: {}", wfRequestToRemove, userId, e);
            return false;
        }
        return true;
    }

    public long searchUsers(String queryString, int from, int size, Map<String, Object> userInfo,
            String deptName, List<String> requestTypes) {
        long totalHits = 0;
        try {
            // Construct the search request
            SearchRequest searchRequest = new SearchRequest(userIndexName);
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.from(from);
            sourceBuilder.size(size);

            // Build the bool query
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // if query is email address then check against
            // profileDetails.personalDetails.primaryEmail
            // if query is valid mobile number then check against
            // profileDetails.personalDetails.mobile
            // if query is none of above use SimpleQueryStringBuilder to get data
            if (ProjectUtil.isValidEmail(queryString)) {
                boolQuery.must(QueryBuilders.termQuery(Constants.PROFILE_PRIMARY_EMAIL_FIELD, queryString));
            } else if (ProjectUtil.isValidMobileNumber(queryString)) {
                boolQuery.must(QueryBuilders.termQuery(Constants.PROFILE_PHONE_NUMBER_FILED, queryString));
            } else {
                SimpleQueryStringBuilder simpleQuery = QueryBuilders.simpleQueryStringQuery(queryString)
                        .defaultOperator(org.elasticsearch.index.query.Operator.OR)
                        .analyzeWildcard(false)
                        .autoGenerateSynonymsPhraseQuery(true)
                        .fuzzyPrefixLength(0)
                        .fuzzyMaxExpansions(50)
                        .fuzzyTranspositions(true)
                        .boost(1.0f);

                for (String field : Constants.USER_DEFAULT_SEARCH_FIELDS) {
                    simpleQuery.field(field);
                }

                boolQuery.must(simpleQuery);
            }

            if (requestTypes.contains(Constants.ORG_TRANSFER_REQUEST)) {
                boolQuery.must(QueryBuilders.termQuery(Constants.WF_TRANSFER_REQUEST_DEPTNAME_KEY, deptName));
            } else {
                BoolQueryBuilder wfProfileQuery = QueryBuilders.boolQuery()
                        .should(QueryBuilders.termQuery(Constants.WF_PROFILE_DESIGNATION_REQUEST_DEPTNAME_KEY,
                                deptName))
                        .should(QueryBuilders.termQuery(Constants.WF_PROFILE_GROUP_REQEST_DEPTNAME_KEY, deptName))
                        .minimumShouldMatch(1); // Ensures at least one of these conditions is met

                boolQuery.must(wfProfileQuery).mustNot(QueryBuilders.existsQuery(Constants.WF_TRANSFER_REQUEST_STRING));
            }

            sourceBuilder.query(boolQuery);

            sourceBuilder.fetchSource(
                    new String[] { Constants.ID, Constants.PROFILE_DETAILS, Constants.ROOT_ORG_ID,
                            Constants.FIRST_NAME_CAMEL_CASE },
                    new String[] {});

            searchRequest.source(sourceBuilder);

            SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

            for (SearchHit hit : searchResponse.getHits().getHits()) {
                Map<String, Object> sourceMap = (Map<String, Object>) hit.getSourceAsMap();
                Map<String, Object> record = new HashMap<>();
                record.put(Constants.ID, sourceMap.get(Constants.ID));
                record.put(Constants.UUID, sourceMap.get(Constants.ID));
                record.put(Constants.ROOT_ORG_ID, sourceMap.get(Constants.ROOT_ORG_ID));
                record.put(Constants.SEARCH_SCORE, hit.getScore());
                HashMap<String, Object> profileDetails = (HashMap<String, Object>) sourceMap
                        .get(Constants.PROFILE_DETAILS);
                if (MapUtils.isNotEmpty(profileDetails)) {
                    HashMap<String, Object> personalDetails = (HashMap<String, Object>) profileDetails
                            .get(Constants.PERSONAL_DETAILS);
                    record.put(Constants.FIRST_NAME, personalDetails.get(Constants.FIRSTNAME));
                    record.put(Constants.EMAIL, personalDetails.get(Constants.PRIMARY_EMAIL));
                    Map<String, Object> additionalProperties = (Map<String, Object>) profileDetails
                            .get(Constants.ADDITIONAL_PROPERTIES);
                    if (MapUtils.isNotEmpty(additionalProperties)) {
                        record.put(Constants.TAG, additionalProperties.get(Constants.TAG));
                    }
                }
                userInfo.put((String) record.get(Constants.ID), record);
            }
            totalHits = searchResponse.getHits().getTotalHits();
        } catch (IOException e) {
            logger.error("Failed to query ES to find records for given query.", e);
        }
        return totalHits;
    }

    public boolean updateWfRequest(String userId, String uuid, boolean append) {
        try {
            // Prepare parameters for the script
            Map<String, Object> params = new HashMap<>();
            params.put("uuid", uuid);

            // Choose the script source based on the operation type.
            // If 'append' is true, then add the UUID if not already present.
            // If 'append' is false, then remove the UUID if it exists.
            String scriptSource;
            if (append) {
                scriptSource = "if (ctx._source.wfRequests == null) { " +
                        "  ctx._source.wfRequests = new ArrayList(); " +
                        "} " +
                        "if (!ctx._source.wfRequests.contains(params.uuid)) { " +
                        "  ctx._source.wfRequests.add(params.uuid); " +
                        "}";
            } else {
                scriptSource = "if (ctx._source.wfRequests != null && ctx._source.wfRequests.contains(params.uuid)) { "
                        +
                        "  ctx._source.wfRequests.remove(ctx._source.wfRequests.indexOf(params.uuid)); " +
                        "}";
            }

            // Create an inline Painless script with the chosen source
            Script script = new Script(ScriptType.INLINE, "painless", scriptSource, params);

            // Define upsert content: if the document does not exist,
            // create it with wfRequests initialized to a list containing the UUID.
            // (For a remove operation, the upsert is less important since there’s nothing
            // to remove.)
            Map<String, Object> upsertContent = new HashMap<>();
            upsertContent.put(Constants.WF_REQUESTS_KEY, Collections.singletonList(uuid));

            // Create the UpdateRequest with a retry_on_conflict setting to handle rapid
            // concurrent updates
            UpdateRequest updateRequest = new UpdateRequest(userIndexName, _DOC, userId)
                    .script(script)
                    .upsert(new IndexRequest(userIndexName).id(userId).source(upsertContent))
                    .retryOnConflict(5);

            // Execute the update
            UpdateResponse updateResponse = client.update(updateRequest, RequestOptions.DEFAULT);
            DocWriteResponse.Result result = updateResponse.getResult();

            if (result == DocWriteResponse.Result.CREATED) {
                logger.info("WfRequests created successfully for userId: {}", userId);
            } else if (result == DocWriteResponse.Result.UPDATED) {
                logger.info("WfRequests updated successfully for userId: {}", userId);
            } else if (result == DocWriteResponse.Result.NOOP) {
                logger.info("WfRequests update was a noop; no changes were made for userId: {}", userId);
            } else {
                logger.warn("WfRequests update:: Unexpected result: {}, for userId: {}", result, userId);
            }
        } catch (Exception e) {
            logger.error("Failed to update wfRequests for userId: {}", userId, e);
            return false;
        }
        return true;
    }

    public boolean updateWfRequestObject(String wfId, String userId, String deptName, String attributeName,
            boolean append) {
        try {
            // Prepare parameters for the script
            Map<String, Object> params = new HashMap<>();
            params.put(Constants.DEPARTMENT_NAME, deptName);
            params.put(Constants.WF_ID_CONSTANT, wfId);

            // Script logic for add or remove
            String scriptSource;
            if (append) {
                scriptSource = "ctx._source." + attributeName + "= new HashMap(); " +
                        "ctx._source." + attributeName + ".departmentName = params.departmentName; " +
                        "ctx._source." + attributeName + ".wfId = params.wfId;";
            } else {
                scriptSource = "ctx._source." + attributeName + " = new HashMap();";
            }

            // Create script object
            Script script = new Script(ScriptType.INLINE, "painless", scriptSource, params);

            // Upsert logic: If document doesn’t exist, initialize it with `attributeName`
            Map<String, Object> upsertContent = new HashMap<>();
            if (append) {
                Map<String, Object> wfRequestObject = new HashMap<>();
                wfRequestObject.put(Constants.DEPARTMENT_NAME, deptName);
                wfRequestObject.put(Constants.WF_ID_CONSTANT, wfId);
                upsertContent.put(attributeName, wfRequestObject);
            } else {
                upsertContent.put(attributeName, new HashMap<>()); // Set as empty map
            }

            // Create UpdateRequest
            UpdateRequest updateRequest = new UpdateRequest(userIndexName, _DOC, userId)
                    .script(script)
                    .upsert(upsertContent) // Creates the document if missing
                    .retryOnConflict(5); // Handle concurrent updates

            // Execute the update
            UpdateResponse updateResponse = client.update(updateRequest, RequestOptions.DEFAULT);

            // Log success
            switch (updateResponse.getResult()) {
                case CREATED:
                    logger.info("{} created successfully for userId: {}", attributeName, userId);
                    break;
                case UPDATED:
                    logger.info("{} updated successfully for userId: {}", attributeName, userId);
                    break;
                case NOOP:
                    logger.info("{} update was a noop; no changes were made for userId: {}", attributeName, userId);
                    break;
                default:
                    logger.warn("{} update:: Unexpected result: {}, for userId: {}",
                            attributeName, updateResponse.getResult(), userId);
            }

            return true;
        } catch (Exception e) {
            logger.error("Failed to update {} for userId: {}", attributeName, userId, e);
            return false;
        }
    }


    /**
     * @param index     name of index
     * @param indexType index type
     * @param entityId  entity Id
     * @return status
     */
    public Map<String, Object> readEntity(String index, String indexType, String entityId) {
        logger.info("readEntity starts with index {} and entityId {}", index, entityId);
        GetResponse response = null;
        try {
            response = client.get(new GetRequest(index, indexType, entityId), RequestOptions.DEFAULT);
        } catch (IOException e) {
            logger.error("Exception in getting the record from ElasticSearch", e);
        }
        if (null == response)
            return null;
        return response.getSourceAsMap();
    }
}
