package org.sunbird.workflow.utils;

import org.sunbird.workflow.models.Response;

import java.util.List;
import java.util.Map;

public interface CassandraOperation {

	List<Map<String, Object>> getRecordsByProperties(String keyspaceName, String tableName,
			Map<String, Object> propertyMap, List<String> fields);

	int getCountByProperties(String keyspaceName, String tableName, Map<String, Object> propertyMap);

	Response insertRecord(String keyspaceName, String tableName, Map<String, Object> request);

	Map<String, Object> updateRecord(String keyspaceName, String tableName, Map<String, Object> updateAttributes,
									 Map<String, Object> compositeKey);
	Map<String, Object> getRecordsByProperties(String keyspaceName, String tableName,
	                                                  Map<String, Object> propertyMap, List<String> fields, String key);
}
