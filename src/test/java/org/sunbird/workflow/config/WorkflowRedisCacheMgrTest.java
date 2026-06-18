package org.sunbird.workflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.utils.CassandraOperation;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowRedisCacheMgrTest {

    @Mock
    private JedisPool jedisPool;

    @Mock
    private WfStatusRepo wfStatusRepo;

    @Mock
    private Configuration configuration;

    @Mock
    private CassandraOperation cassandraOperation;

    @Mock
    private Jedis jedis;

    private WorkflowRedisCacheMgr cacheMgr;

    @Captor
    private ArgumentCaptor<Map<String, String>> mapCaptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        cacheMgr = new WorkflowRedisCacheMgr(jedisPool, wfStatusRepo, configuration, cassandraOperation);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(configuration.getBpBatchStatsCacheTtl()).thenReturn(14400);
        when(configuration.getBpBatchStatsCacheIndex()).thenReturn(2);
        // Default: no active enrollments in Cassandra → approved = 0
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(Collections.emptyList());
    }


    @Test
    void put_setsKeyValueAndExpiry() {
        cacheMgr.put("myKey", "myValue", 300, 2);
        verify(jedis).select(2);
        verify(jedis).set("myKey", "myValue");
        verify(jedis).expire("myKey", 300);
    }

    @Test
    void put_doesNotThrowWhenRedisUnavailable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection refused"));
        assertDoesNotThrow(() -> cacheMgr.put("k", "v", 60, 0));
    }

    @Test
    void get_returnsValueFromRedis() {
        when(jedis.get("myKey")).thenReturn("cached");
        String result = cacheMgr.get("myKey", 1);
        assertEquals("cached", result);
        verify(jedis).select(1);
    }

    @Test
    void get_returnsNullWhenRedisUnavailable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Connection refused"));
        assertNull(cacheMgr.get("myKey", 0));
    }

    @Test
    void increment_whenKeyExists_hincrByWithoutInitialisingFromDb() {
        String batchId = "batch-001";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(true);
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        verify(wfStatusRepo, never()).countGroupedByStatusForApplicationId(any());
        verify(cassandraOperation, never()).getRecordsByProperties(any(), any(), any(), any());
        verify(jedis).hincrBy(key, Constants.BATCH_STATS_FIELD_PENDING, 1L);
    }


    @Test
    void increment_whenKeyMissing_initFromDbAndSkipIncrBy() {
        String batchId = "batch-002";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        // ENROLL_IS_IN_PROGRESS is non-terminal → counts as pending
        // APPROVED is terminal → skipped for pending; approved comes from Cassandra (empty → 0)
        // WITHDRAWN is terminal → counted as withdrawn
        List<Object[]> dbRows = new ArrayList<>();
        dbRows.add(new Object[]{"ENROLL_IS_IN_PROGRESS", 3L});
        dbRows.add(new Object[]{"APPROVED", 2L});
        dbRows.add(new Object[]{"WITHDRAWN", 1L});
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(dbRows);
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        // pending = 3, withdrawn = 1, rejected = 0, approved = 0 (Cassandra returned empty)
        verify(jedis).hmset(key, Map.of(
                Constants.BATCH_STATS_FIELD_PENDING, "3",
                Constants.BATCH_STATS_FIELD_WITHDRAWN, "1",
                Constants.BATCH_STATS_FIELD_REJECTED, "0",
                Constants.BATCH_STATS_FIELD_APPROVED, "0"
        ));
        verify(jedis, never()).hincrBy(any(String.class), any(String.class), anyLong());
    }

    @Test
    void increment_whenDbReturnsOnlyTerminalStatuses_pendingAndWithdrawnAreZero() {
        String batchId = "batch-003";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        List<Object[]> dbRows = new ArrayList<>();
        dbRows.add(new Object[]{"APPROVED", 10L});
        dbRows.add(new Object[]{"REJECTED", 5L});
        dbRows.add(new Object[]{"REMOVED", 2L});
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(dbRows);
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        verify(jedis).hmset(key, Map.of(
                Constants.BATCH_STATS_FIELD_PENDING, "0",
                Constants.BATCH_STATS_FIELD_WITHDRAWN, "0",
                Constants.BATCH_STATS_FIELD_REJECTED, "5",
                Constants.BATCH_STATS_FIELD_APPROVED, "0"
        ));
        verify(jedis, never()).hincrBy(any(String.class), any(String.class), anyLong());
    }

    @Test
    void increment_whenDbReturnsEmptyRows_zeroCounts() {
        String batchId = "batch-004";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(Collections.emptyList());
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        verify(jedis).hmset(key, Map.of(
                Constants.BATCH_STATS_FIELD_PENDING, "0",
                Constants.BATCH_STATS_FIELD_WITHDRAWN, "0",
                Constants.BATCH_STATS_FIELD_REJECTED, "0",
                Constants.BATCH_STATS_FIELD_APPROVED, "0"
        ));
        verify(jedis, never()).hincrBy(any(String.class), any(String.class), anyLong());
    }

    @Test
    void increment_doesNotThrowWhenRedisUnavailable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));
        assertDoesNotThrow(() -> cacheMgr.incrementBatchFieldCount("b1", Constants.BATCH_STATS_FIELD_PENDING));
    }

    @Test
    void decrement_whenKeyExists_hincrByNegativeOne() {
        String batchId = "batch-005";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(true);
        cacheMgr.decrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        verify(jedis).hincrBy(key, Constants.BATCH_STATS_FIELD_PENDING, -1L);
    }

    @Test
    void decrement_whenKeyMissing_initFromDbAndSkipDecrement() {
        String batchId = "batch-006";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        List<Object[]> dbRows = new ArrayList<>();
        dbRows.add(new Object[]{"WITHDRAWN", 1L});
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(dbRows);
        cacheMgr.decrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        verify(jedis, never()).hincrBy(any(String.class), any(String.class), anyLong());
        verify(wfStatusRepo).countGroupedByStatusForApplicationId(batchId);
        verify(jedis).hmset(eq(key), argThat(m ->
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_PENDING)) &&
                "1".equals(m.get(Constants.BATCH_STATS_FIELD_WITHDRAWN)) &&
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_REJECTED)) &&
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_APPROVED))
        ));
    }

    @Test
    void decrement_doesNotThrowWhenRedisUnavailable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));
        assertDoesNotThrow(() -> cacheMgr.decrementBatchFieldCount("b1", Constants.BATCH_STATS_FIELD_PENDING));
    }

    @Test
    void getBatchStats_whenKeyExists_returnsHgetAllWithoutDbInit() {
        String batchId = "batch-007";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(true);
        Map<String, String> expected = Map.of("pending", "7", "withdrawn", "2");
        when(jedis.hgetAll(key)).thenReturn(expected);
        Map<String, String> result = cacheMgr.getBatchStats(batchId);
        assertEquals(expected, result);
        verify(wfStatusRepo, never()).countGroupedByStatusForApplicationId(any());
        verify(cassandraOperation, never()).getRecordsByProperties(any(), any(), any(), any());
    }

    @Test
    void getBatchStats_whenKeyMissing_initFromDbThenReturnHgetAll() {
        String batchId = "batch-008";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        List<Object[]> dbRows = new ArrayList<>();
        dbRows.add(new Object[]{"ENROLL_IS_IN_PROGRESS", 5L});
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(dbRows);
        Map<String, String> expected = Map.of("pending", "5", "withdrawn", "0");
        when(jedis.hgetAll(key)).thenReturn(expected);
        Map<String, String> result = cacheMgr.getBatchStats(batchId);
        verify(jedis).hmset(eq(key), argThat(m ->
                "5".equals(m.get(Constants.BATCH_STATS_FIELD_PENDING)) &&
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_WITHDRAWN)) &&
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_REJECTED)) &&
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_APPROVED))
        ));
        assertEquals(expected, result);
    }

    @Test
    void getBatchStats_whenRedisUnavailable_returnsEmptyMap() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("Redis down"));
        Map<String, String> result = cacheMgr.getBatchStats("batch-009");
        assertEquals(Collections.emptyMap(), result);
    }

    @Test
    void increment_withdrawnStatusInDb_isCountedAsWithdrawnNotPending() {
        String batchId = "batch-010";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        List<Object[]> dbRows = new ArrayList<>();
        dbRows.add(new Object[]{"WITHDRAWN", 3L});
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(dbRows);
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_WITHDRAWN);
        verify(jedis).hmset(eq(key), mapCaptor.capture());
        Map<String, String> stored = mapCaptor.getValue();
        assertEquals("3", stored.get(Constants.BATCH_STATS_FIELD_WITHDRAWN));
        assertEquals("0", stored.get(Constants.BATCH_STATS_FIELD_PENDING));
        assertEquals("0", stored.get(Constants.BATCH_STATS_FIELD_REJECTED));
        assertEquals("0", stored.get(Constants.BATCH_STATS_FIELD_APPROVED));
    }

    @Test
    void increment_rejectedStatusInDb_isCountedAsRejectedNotPending() {
        String batchId = "batch-011";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        List<Object[]> dbRows = new ArrayList<>();
        dbRows.add(new Object[]{"REJECTED", 4L});
        dbRows.add(new Object[]{"ENROLL_IS_IN_PROGRESS", 2L});
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(dbRows);
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_PENDING);
        verify(jedis).hmset(eq(key), mapCaptor.capture());
        Map<String, String> stored = mapCaptor.getValue();
        assertEquals("4", stored.get(Constants.BATCH_STATS_FIELD_REJECTED));
        assertEquals("2", stored.get(Constants.BATCH_STATS_FIELD_PENDING));
        assertEquals("0", stored.get(Constants.BATCH_STATS_FIELD_WITHDRAWN));
        assertEquals("0", stored.get(Constants.BATCH_STATS_FIELD_APPROVED));
    }

    @Test
    void increment_whenCassandraReturnsActiveEnrollments_approvedCountReflectsIt() {
        String batchId = "batch-012";
        String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        when(jedis.exists(key)).thenReturn(false);
        when(wfStatusRepo.countGroupedByStatusForApplicationId(batchId)).thenReturn(Collections.emptyList());
        // Cassandra returns 3 active + 1 inactive enrollment
        List<Map<String, Object>> cassandraRows = new ArrayList<>();
        cassandraRows.add(Map.of(Constants.ACTIVE, true));
        cassandraRows.add(Map.of(Constants.ACTIVE, true));
        cassandraRows.add(Map.of(Constants.ACTIVE, true));
        cassandraRows.add(Map.of(Constants.ACTIVE, false));
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList()))
                .thenReturn(cassandraRows);
        cacheMgr.incrementBatchFieldCount(batchId, Constants.BATCH_STATS_FIELD_APPROVED);
        verify(jedis).hmset(eq(key), argThat(m ->
                "3".equals(m.get(Constants.BATCH_STATS_FIELD_APPROVED)) &&
                "0".equals(m.get(Constants.BATCH_STATS_FIELD_PENDING))
        ));
    }
}
