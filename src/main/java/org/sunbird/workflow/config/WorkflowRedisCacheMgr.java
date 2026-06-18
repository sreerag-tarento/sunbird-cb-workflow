package org.sunbird.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.utils.CassandraOperation;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowRedisCacheMgr {

    private final JedisPool jedisPool;
    private final WfStatusRepo wfStatusRepo;
    private final Configuration configuration;
    private final CassandraOperation cassandraOperation;
    private final Logger logger = LoggerFactory.getLogger(WorkflowRedisCacheMgr.class);

    public WorkflowRedisCacheMgr(@Qualifier("jedisWorkflowPopulationPool") JedisPool jedisPool,
                                 WfStatusRepo wfStatusRepo,
                                 Configuration configuration,
                                 CassandraOperation cassandraOperation) {
        this.jedisPool = jedisPool;
        this.wfStatusRepo = wfStatusRepo;
        this.configuration = configuration;
        this.cassandraOperation = cassandraOperation;
    }

    public void put(String key, String value, int ttl, int index) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(index);
            jedis.set(key, value);
            jedis.expire(key, ttl);
            logger.debug("Cache_key_value {} is saved in redis", key);
        } catch (Exception e) {
            logger.error("An error occurred while saving data into Redis", e);
        }
    }

    public String get(String key, int index) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(index);
            return jedis.get(key);
        } catch (Exception e) {
            logger.error("An Error Occurred while getting content from cache", e);
            return null;
        }
    }

    /**
     * Lazy-initialises the batch stats Redis hash from the DB.
     * pending/withdrawn/rejected come from wf_status (PostgreSQL).
     * approved comes from enrollment_batch_lookup (Cassandra) — source of truth for actual enrollments.
     */
    private void initBatchStatsFromDb(String batchId, Jedis jedis, String key) {
        logger.debug("Cache miss for batchId={}, initialising batch stats from DB", batchId);
        var rows = wfStatusRepo.countGroupedByStatusForApplicationId(batchId);
        long pending = 0L;
        long withdrawn = 0L;
        long rejected = 0L;
        for (var row : rows) {
            var status = (String) row[0];
            var count = ((Number) row[1]).longValue();
            if (!Constants.BATCH_STATS_TERMINAL_STATUSES.contains(status)) {
                pending += count;
            }
            if (Constants.WITHDRAWN.equalsIgnoreCase(status)) {
                withdrawn = count;
            }
            if (Constants.REJECTED.equalsIgnoreCase(status)) {
                rejected = count;
            }
        }
        long approved = countActiveEnrollments(batchId);
        jedis.hmset(key, Map.of(
                Constants.BATCH_STATS_FIELD_PENDING, String.valueOf(pending),
                Constants.BATCH_STATS_FIELD_WITHDRAWN, String.valueOf(withdrawn),
                Constants.BATCH_STATS_FIELD_REJECTED, String.valueOf(rejected),
                Constants.BATCH_STATS_FIELD_APPROVED, String.valueOf(approved)));
        jedis.expire(key, configuration.getBpBatchStatsCacheTtl());
        logger.info("Batch stats cache initialised for batchId={} pending={} withdrawn={} rejected={} approved={} ttl={}s",
                batchId, pending, withdrawn, rejected, approved, configuration.getBpBatchStatsCacheTtl());
    }

    /**
     * Increments the given field in the batch stats hash by 1.
     *
     * On cache miss: initialises from DB and returns without applying a delta,
     * because the Kafka event is always published AFTER the DB write — so the
     * DB snapshot already reflects this state change. Applying +1 on top would
     * double-count the record.
     *
     * On cache hit: the cache was built before this change arrived, so the
     * delta is applied normally to keep the cache in sync.
     */
    public void incrementBatchFieldCount(String batchId, String field) {
        var key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(configuration.getBpBatchStatsCacheIndex());
            if (!jedis.exists(key)) {
                initBatchStatsFromDb(batchId, jedis, key);
                logger.debug("Cache miss for batchId={}: initialised from DB (skipping increment for field={})", batchId, field);
                return;
            }
            var updated = jedis.hincrBy(key, field, 1L);
            logger.debug("Incremented field={} for batchId={} newValue={}", field, batchId, updated);
        } catch (Exception e) {
            logger.error("Failed to increment {} count in cache for batchId={}", field, batchId, e);
        }
    }

    /**
     * Decrements the given field in the batch stats hash by 1.
     *
     * On cache miss: initialises from DB and returns without applying a delta,
     * because the DB already reflects this state change (event published after
     * DB write). Applying -1 on top would under-count the record.
     *
     * On cache hit: apply the delta normally to keep the cache in sync.
     */
    public void decrementBatchFieldCount(String batchId, String field) {
        var key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(configuration.getBpBatchStatsCacheIndex());
            if (!jedis.exists(key)) {
                initBatchStatsFromDb(batchId, jedis, key);
                logger.debug("Cache miss for batchId={}: initialised from DB (skipping decrement for field={})", batchId, field);
                return;
            }
            var updated = jedis.hincrBy(key, field, -1L);
            logger.debug("Decremented field={} for batchId={} newValue={}", field, batchId, updated);
        } catch (Exception e) {
            logger.error("Failed to decrement {} count in cache for batchId={}", field, batchId, e);
        }
    }

    /**
     * Returns all stats fields for the given batch from the Redis hash.
     * Initialises from the DB on cache miss.
     */
    public Map<String, String> getBatchStats(String batchId) {
        var key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(configuration.getBpBatchStatsCacheIndex());
            if (!jedis.exists(key)) {
                initBatchStatsFromDb(batchId, jedis, key);
            }
            return jedis.hgetAll(key);
        } catch (Exception e) {
            logger.error("Failed to read batch stats from cache for batchId={}", batchId, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Counts active (enrolled) participants for the given batchId from Cassandra
     * enrollment_batch_lookup — the same source of truth used by sunbird-course-service.
     */
    private long countActiveEnrollments(String batchId) {
        logger.info("BatchStats: countActiveEnrollments :: batchId={}", batchId);
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.BATCH_ID, batchId);
        List<Map<String, Object>> rows = cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_ENROLMENT_BATCH_LOOKUP,
                propertyMap,
                List.of(Constants.ACTIVE));
        long count = rows.stream()
                .filter(r -> r != null && Boolean.TRUE.equals(r.get(Constants.ACTIVE)))
                .count();
        logger.info("BatchStats: countActiveEnrollments :: batchId={} totalRows={} activeCount={}", batchId, rows.size(), count);
        return count;
    }
}
