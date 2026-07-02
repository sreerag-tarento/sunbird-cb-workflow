package org.sunbird.workflow.config;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.sunbird.workflow.postgres.repo.WfStatusRepo;
import org.sunbird.workflow.utils.CassandraOperation;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class WorkflowRedisCacheMgr {

    private final JedisPool jedisPool;
    private final WfStatusRepo wfStatusRepo;
    private final Configuration configuration;
    private final CassandraOperation cassandraOperation;
    private final Logger logger = LoggerFactory.getLogger(WorkflowRedisCacheMgr.class);

    public WorkflowRedisCacheMgr(JedisPool jedisPool,
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

    /**
     * Bulk warm-up: initialises Redis stats for a chunk of batchIds in 1 PG + 1 Cassandra query.
     * Only processes batchIds whose key has expired (cache miss); live keys are untouched.
     * Called by the sync job which partitions all batch IDs into configurable-sized chunks.
     */
    public void bulkInitBatchStats(List<String> batchIds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(configuration.getBpBatchStatsCacheIndex());
            List<String> missing = findMissingBatchIds(batchIds, jedis);
            if (CollectionUtils.isEmpty(missing)) {
                logger.info("BatchStats: bulkInitBatchStats :: all {} keys still live, skipping", batchIds.size());
                return;
            }
            logger.info("BatchStats: bulkInitBatchStats :: missingCount={} of chunkSize={}", missing.size(), batchIds.size());
            Map<String, long[]> pgStats = buildPgStatsPerBatch(wfStatusRepo.countGroupedByStatusForApplicationIds(missing));
            Map<String, Long> approvedMap = fetchApprovedCountsFromCassandra(missing);
            writeStatsToRedis(missing, pgStats, approvedMap, jedis);
            logger.info("BatchStats: bulkInitBatchStats :: initialized {} Redis keys", missing.size());
        } catch (Exception e) {
            logger.error("BatchStats: bulkInitBatchStats :: error for chunk of size={}", batchIds.size(), e);
        }
    }

    /**
     * Aggregates raw wf_status rows — each row is [application_id, current_status, count] —
     * into a map of batchId → long[3] where indices are [pending, withdrawn, rejected].
     * Non-terminal statuses (anything not in BATCH_STATS_TERMINAL_STATUSES) contribute to pending.
     */
    private Map<String, long[]> buildPgStatsPerBatch(List<Object[]> rows) {
        Map<String, long[]> statsMap = new HashMap<>();
        rows.forEach(row -> {
            String batchId = (String) row[0];
            String status = (String) row[1];
            long count = ((Number) row[2]).longValue();
            long[] stats = statsMap.computeIfAbsent(batchId, k -> new long[3]);
            if (!Constants.BATCH_STATS_TERMINAL_STATUSES.contains(status)) stats[0] += count;
            if (Constants.WITHDRAWN.equalsIgnoreCase(status)) stats[1] = count;
            if (Constants.REJECTED.equalsIgnoreCase(status)) stats[2] = count;
        });
        logger.debug("BatchStats: buildPgStatsPerBatch :: processedRows={} distinctBatches={}", rows.size(), statsMap.size());
        return statsMap;
    }

    /**
     * Checks which batch stat keys are missing in Redis using a single pipeline round-trip,
     * avoiding one EXISTS call per batch ID.
     */
    private List<String> findMissingBatchIds(List<String> batchIds, Jedis jedis) {
        Pipeline pipeline = jedis.pipelined();
        List<redis.clients.jedis.Response<Boolean>> responses = batchIds.stream()
                .map(id -> pipeline.exists(Constants.BP_BATCH_STATS_PREFIX + id))
                .toList();
        pipeline.sync();
        List<String> missing = IntStream.range(0, batchIds.size())
                .filter(i -> !responses.get(i).get())
                .mapToObj(batchIds::get)
                .toList();
        logger.debug("BatchStats: findMissingBatchIds :: checked={} missing={}", batchIds.size(), missing.size());
        return missing;
    }

    /**
     * Fetches enrollment_batch_lookup rows for all given batch IDs in a single Cassandra IN query
     * and counts active=true rows per batch ID to produce the approved count.
     */
    private Map<String, Long> fetchApprovedCountsFromCassandra(List<String> batchIds) {
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.BATCH_ID, batchIds);
        List<Map<String, Object>> rows = cassandraOperation.getRecordsByProperties(
                Constants.KEYSPACE_SUNBIRD_COURSES,
                Constants.TABLE_ENROLMENT_BATCH_LOOKUP,
                propertyMap,
                List.of(Constants.BATCH_ID_KEY, Constants.ACTIVE));
        Map<String, Long> approvedMap = rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.get(Constants.ACTIVE)))
                .filter(r -> StringUtils.isNotBlank((String) r.get(Constants.BATCH_ID_KEY)))
                .collect(Collectors.groupingBy(r -> (String) r.get(Constants.BATCH_ID_KEY), Collectors.counting()));
        logger.info("BatchStats: fetchApprovedCountsFromCassandra :: queriedBatches={} totalRows={} batchesWithActiveEnrollments={}", batchIds.size(), rows.size(), approvedMap.size());
        return approvedMap;
    }

    /**
     * Partitions batchIds into sub-chunks of bp.batch.stats.sync.redis.pipeline.size
     * and flushes each sub-chunk in a single pipeline round-trip, decoupling Redis pipeline
     * size from the DB chunk size so both can be tuned independently.
     */
    private void writeStatsToRedis(List<String> batchIds, Map<String, long[]> pgStats,
                                   Map<String, Long> approvedMap, Jedis jedis) {
        int pipelineSize = configuration.getBpBatchStatsSyncRedisPipelineSize();
        int totalPipelines = (int) Math.ceil((double) batchIds.size() / pipelineSize);
        logger.info("BatchStats: writeStatsToRedis :: started totalKeys={} pipelineSize={} totalPipelines={}", batchIds.size(), pipelineSize, totalPipelines);
        IntStream.range(0, totalPipelines).forEach(i -> {
            List<String> subChunk = batchIds.subList(i * pipelineSize, Math.min((i + 1) * pipelineSize, batchIds.size()));
            logger.info("BatchStats: writeStatsToRedis :: pipeline {}/{} keys={}", i + 1, totalPipelines, subChunk.size());
            flushPipeline(subChunk, pgStats, approvedMap, jedis);
        });
        logger.info("BatchStats: writeStatsToRedis :: completed totalKeysFlushed={} totalPipelines={}", batchIds.size(), totalPipelines);
    }

    /**
     * Writes hmset + expire for each batch ID in a single pipeline round-trip.
     * Defaults to zero counts for any batch absent from the DB result maps.
     */
    private void flushPipeline(List<String> subChunk, Map<String, long[]> pgStats,
                               Map<String, Long> approvedMap, Jedis jedis) {
        Pipeline pipeline = jedis.pipelined();
        List<String> toWrite = subChunk.stream()
                .filter(batchId -> {
                    long[] s = pgStats.getOrDefault(batchId, new long[3]);
                    long approved = approvedMap.getOrDefault(batchId, 0L);
                    boolean hasActivity = s[0] != 0 || s[1] != 0 || s[2] != 0 || approved != 0;
                    if (!hasActivity) logger.debug("BatchStats: flushPipeline :: skipping batchId={} all counts are zero", batchId);
                    return hasActivity;
                })
                .toList();
        toWrite.forEach(batchId -> {
            long[] s = pgStats.getOrDefault(batchId, new long[3]);
            long approved = approvedMap.getOrDefault(batchId, 0L);
            String key = Constants.BP_BATCH_STATS_PREFIX + batchId;
            pipeline.hmset(key, Map.of(
                    Constants.BATCH_STATS_FIELD_PENDING, String.valueOf(s[0]),
                    Constants.BATCH_STATS_FIELD_WITHDRAWN, String.valueOf(s[1]),
                    Constants.BATCH_STATS_FIELD_REJECTED, String.valueOf(s[2]),
                    Constants.BATCH_STATS_FIELD_APPROVED, String.valueOf(approved)));
            pipeline.expire(key, configuration.getBpBatchStatsCacheTtl());
            logger.debug("BatchStats: flushPipeline :: queued batchId={} pending={} withdrawn={} rejected={} approved={}", batchId, s[0], s[1], s[2], approved);
        });
        pipeline.sync();
        logger.debug("BatchStats: flushPipeline :: synced={} skipped={} in subChunk={}", toWrite.size(), subChunk.size() - toWrite.size(), subChunk.size());
    }
}
