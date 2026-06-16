package org.sunbird.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import java.util.Collections;
import java.util.Map;

@Component
public class WorkflowRedisCacheMgr {

    private final JedisPool jedisPool;
    private final WfStatusRepo wfStatusRepo;
    private final Configuration configuration;
    private final Logger logger = LoggerFactory.getLogger(WorkflowRedisCacheMgr.class);

    public WorkflowRedisCacheMgr(@Qualifier("jedisWorkflowPopulationPool") JedisPool jedisPool,
                                 WfStatusRepo wfStatusRepo,
                                 Configuration configuration) {
        this.jedisPool = jedisPool;
        this.wfStatusRepo = wfStatusRepo;
        this.configuration = configuration;
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
     * Queries wf_status grouped by current_status for the given batchId,
     * tallies pending (any non-terminal status) and withdrawn counts,
     * then writes both fields into the hash in a single HMSET.
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
        jedis.hmset(key, Map.of(
                Constants.BATCH_STATS_FIELD_PENDING, String.valueOf(pending),
                Constants.BATCH_STATS_FIELD_WITHDRAWN, String.valueOf(withdrawn),
                Constants.BATCH_STATS_FIELD_REJECTED, String.valueOf(rejected)));
        jedis.expire(key, configuration.getBpBatchStatsCacheTtl());
        logger.info("Batch stats cache initialised for batchId={} pending={} withdrawn={} rejected={} ttl={}s",
                batchId, pending, withdrawn, rejected, configuration.getBpBatchStatsCacheTtl());
    }

    /**
     * Increments the given field in the batch stats hash by 1.
     * If the key does not exist, initialises it from the DB first (lazy init).
     */
    public void incrementBatchFieldCount(String batchId, String field) {
        var key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(configuration.getBpBatchStatsCacheIndex());
            if (!jedis.exists(key)) {
                initBatchStatsFromDb(batchId, jedis, key);
            }
            var updated = jedis.hincrBy(key, field, 1L);
            logger.debug("Incremented field={} for batchId={} newValue={}", field, batchId, updated);
        } catch (Exception e) {
            logger.error("Failed to increment {} count in cache for batchId={}", field, batchId, e);
        }
    }

    /**
     * Decrements the given field in the batch stats hash by 1.
     * No-op if the key does not exist — avoids creating a stale entry on decrement.
     */
    public void decrementBatchFieldCount(String batchId, String field) {
        var key = Constants.BP_BATCH_STATS_PREFIX + batchId;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(configuration.getBpBatchStatsCacheIndex());
            if (!jedis.exists(key)) {
                logger.debug("Skipping decrement for field={} batchId={} — key not in cache", field, batchId);
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
}
