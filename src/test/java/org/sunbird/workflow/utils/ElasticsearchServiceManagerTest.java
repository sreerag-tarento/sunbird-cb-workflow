package org.sunbird.workflow.utils;

import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
        import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.util.*;

        import static org.junit.jupiter.api.Assertions.*;
        import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class ElasticsearchServiceManagerTest {


    @InjectMocks
    private ElasticsearchServiceManager service;

    @Mock
    private RestHighLevelClient client;

    @Mock
    private SearchResponse searchResponse;

    @Mock
    private SearchHits searchHits;

    @Mock
    private SearchHit searchHit;

    @Mock
    private UpdateResponse updateResponse;

    @Mock
    private GetResponse getResponse;

    private static final String INDEX = "user_index";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "userIndexName", INDEX);
    }

    @Test
    void testUpsertWfRequest_success() throws Exception {
        String userId = "user123";
        List<String> wfRequests = List.of("req1", "req2");

        boolean result = service.upsertWfRequest(userId, wfRequests);

        assertTrue(result);
        verify(client, times(1)).update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT));
    }

    @Test
    void testUpsertWfRequest_failure() throws Exception {
        String userId = "user123";
        List<String> wfRequests = List.of("req1");

        doThrow(new IOException("test")).when(client).update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT));

        boolean result = service.upsertWfRequest(userId, wfRequests);

        assertFalse(result);
        verify(client, times(1)).update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT));
    }

    @Test
    void testRemoveWfRequest_success() throws Exception {
        String userId = "user123";
        String wfId = "req1";
        Map<String, Object> sourceMap = new HashMap<>();
        sourceMap.put("wfRequests", new ArrayList<>(List.of("req1", "req2")));

        when(client.get(any(), eq(RequestOptions.DEFAULT))).thenReturn(getResponse);
        when(getResponse.isExists()).thenReturn(true);
        when(getResponse.getSourceAsMap()).thenReturn(sourceMap);

        boolean result = service.removeWfRequest(userId, wfId);

        assertTrue(result);
        verify(client, times(1)).get(any(), eq(RequestOptions.DEFAULT));
        verify(client, times(1)).update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT));
    }

    @Test
    void testRemoveWfRequest_notFound() throws Exception {
        when(client.get(any(), eq(RequestOptions.DEFAULT))).thenReturn(getResponse);
        when(getResponse.isExists()).thenReturn(false);

        boolean result = service.removeWfRequest("user123", "req1");

        assertFalse(result);
        verify(client, times(1)).get(any(), eq(RequestOptions.DEFAULT));
        verify(client, never()).update(any(), eq(RequestOptions.DEFAULT));
    }

    @Test
    void testRemoveWfRequest_listNull() throws Exception {
        Map<String, Object> sourceMap = new HashMap<>();
        sourceMap.put("wfRequests", null);

        when(client.get(any(), eq(RequestOptions.DEFAULT))).thenReturn(getResponse);
        when(getResponse.isExists()).thenReturn(true);
        when(getResponse.getSourceAsMap()).thenReturn(sourceMap);

        boolean result = service.removeWfRequest("user123", "req1");

        assertFalse(result);
    }

    @Test
    void testRemoveWfRequest_listDoesNotContainItem() throws Exception {
        Map<String, Object> sourceMap = new HashMap<>();
        sourceMap.put("wfRequests", new ArrayList<>(List.of("req2")));

        when(client.get(any(), eq(RequestOptions.DEFAULT))).thenReturn(getResponse);
        when(getResponse.isExists()).thenReturn(true);
        when(getResponse.getSourceAsMap()).thenReturn(sourceMap);

        boolean result = service.removeWfRequest("user123", "req1");

        assertFalse(result);
    }

    @Test
    void testRemoveWfRequest_updateFails() throws Exception {
        String userId = "user123";
        String wfId = "req1";
        Map<String, Object> sourceMap = new HashMap<>();
        sourceMap.put("wfRequests", new ArrayList<>(List.of("req1")));

        when(client.get(any(), eq(RequestOptions.DEFAULT))).thenReturn(getResponse);
        when(getResponse.isExists()).thenReturn(true);
        when(getResponse.getSourceAsMap()).thenReturn(sourceMap);
        doThrow(new IOException("update failed")).when(client).update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT));

        boolean result = service.removeWfRequest(userId, wfId);

        assertFalse(result);
    }

    @Test
    void testSearchUsers_validQuery() throws IOException {
        Map<String, Object> userInfo = new HashMap<>();
        List<String> requestTypes = List.of("someOtherRequest");
        String query = "test";

        // Mutable maps instead of Map.of(...) to avoid ClassCastException
        Map<String, Object> personal = new HashMap<>();
        personal.put("firstName", "John");
        personal.put("primaryEmail", "john@example.com");

        Map<String, Object> additional = new HashMap<>();
        additional.put("tag", "engineering");

        Map<String, Object> profile = new HashMap<>();
        profile.put("personalDetails", personal);
        profile.put("additionalProperties", additional);

        Map<String, Object> sourceMap = new HashMap<>();
        sourceMap.put("id", "123");
        sourceMap.put("profileDetails", profile);
        sourceMap.put("rootOrgId", "org1");

        // Mocking Elasticsearch response
        when(client.search(any(SearchRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(searchResponse);
        when(searchResponse.getHits()).thenReturn(searchHits);
        when(searchHits.getHits()).thenReturn(new SearchHit[]{searchHit});
        when(searchHits.getTotalHits()).thenReturn(1L);
        when(searchHit.getSourceAsMap()).thenReturn(sourceMap);
        when(searchHit.getScore()).thenReturn(1.0f);

        long result = service.searchUsers(query, 0, 10, userInfo, "IT", requestTypes);

        assertEquals(1L, result);
        assertTrue(userInfo.containsKey("123"));
    }

    @Test
    void testUpdateWfRequest_append_true() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.UPDATED);

        boolean result = service.updateWfRequest("user123", "uuid123", true);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequest_append_false() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.CREATED);

        boolean result = service.updateWfRequest("user123", "uuid123", false);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequest_append_false_withUpdated() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.UPDATED);

        boolean result = service.updateWfRequest("user123", "uuid123", false);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequest_append_false_withNoop() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.NOOP);

        boolean result = service.updateWfRequest("user123", "uuid123", false);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequest_append_false_withNotFound() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.NOT_FOUND);

        boolean result = service.updateWfRequest("user123", "uuid123", false);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequest_exception() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenThrow(IOException.class);

        boolean result = service.updateWfRequest("user123", "uuid123", true);
        assertFalse(result);
    }

    @Test
    void testUpdateWfRequestObject_append_true() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.UPDATED);

        boolean result = service.updateWfRequestObject("wf1", "user1", "dept1", "attr1", true);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequestObject_append_true_Created() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.CREATED);

        boolean result = service.updateWfRequestObject("wf1", "user1", "dept1", "attr1", true);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequestObject_append_true_Default() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.NOT_FOUND);

        boolean result = service.updateWfRequestObject("wf1", "user1", "dept1", "attr1", true);
        assertTrue(result);
    }
    @Test
    void testUpdateWfRequestObject_append_false() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenReturn(updateResponse);
        when(updateResponse.getResult()).thenReturn(DocWriteResponse.Result.NOOP);

        boolean result = service.updateWfRequestObject("wf1", "user1", "dept1", "attr1", false);
        assertTrue(result);
    }

    @Test
    void testUpdateWfRequestObject_exception() throws IOException {
        when(client.update(any(UpdateRequest.class), eq(RequestOptions.DEFAULT))).thenThrow(IOException.class);

        boolean result = service.updateWfRequestObject("wf1", "user1", "dept1", "attr1", false);
        assertFalse(result);
    }


    @Test
    void testReadEntity_Success() throws Exception {
        // Arrange
        String index = "user";
        String indexType = "_doc";
        String entityId = "123";

        GetResponse response = Mockito.mock(GetResponse.class);

        Map<String, Object> source = new HashMap<>();
        source.put("name", "TestUser");

        when(response.getSourceAsMap()).thenReturn(source);
        when(client.get(any(GetRequest.class), eq(RequestOptions.DEFAULT)))
                .thenReturn(response);

        // Act
        Map<String, Object> result = service.readEntity(index, indexType, entityId);

        // Assert
        assertNotNull(result);
        assertEquals("TestUser", result.get("name"));

        verify(client).get(any(GetRequest.class), eq(RequestOptions.DEFAULT));
    }

    @Test
    void testReadEntity_WhenResponseIsNull_ReturnsNull() throws Exception {

        when(client.get(any(GetRequest.class), eq(RequestOptions.DEFAULT)))
                .thenReturn(null);

        Map<String, Object> result =
                service.readEntity("user", "_doc", "123");

        assertNull(result);
    }

    @Test
    void testReadEntity_WhenIOException_ReturnsNull() throws Exception {

        when(client.get(any(GetRequest.class), eq(RequestOptions.DEFAULT)))
                .thenThrow(new IOException("Connection failed"));

        Map<String, Object> result =
                service.readEntity("user", "_doc", "123");

        assertNull(result);
    }
}
