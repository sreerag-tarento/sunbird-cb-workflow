package org.sunbird.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;


@Component
public class RedisCacheMgr {

    private static Integer cache_ttl = 84600;

    @Autowired
    private JedisPool jedisPool;

    ObjectMapper objectMapper = new ObjectMapper();

    private final Logger logger = LoggerFactory.getLogger(RedisCacheMgr.class);


    public void putCache(String key, String[] values, Integer ttl) {
        try (Jedis jedis = jedisPool.getResource()) {
            if(null == ttl)
                ttl = cache_ttl;
            jedis.sadd(Constants.REDIS_COMMON_KEY + key, values);
            jedis.expire(Constants.REDIS_COMMON_KEY + key, ttl);
            logger.debug("Cache_key_value " + Constants.REDIS_COMMON_KEY + key + " is saved in redis");
        } catch (Exception e) {
            logger.error("An error occurred while saving data into Redis",e);
        }
    }

    public boolean keyExists(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(Constants.REDIS_COMMON_KEY + key);
        } catch (Exception e) {
            logger.error("An Error Occurred while fetching value from Redis", e);
            return false;
        }
    }

    public boolean valueExists(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.sismember(Constants.REDIS_COMMON_KEY + key, value);
        } catch (Exception e) {
            logger.error("An Error Occurred while fetching value from Redis", e);
            return false;
        }
    }

    public void putInBasicProfileCache(String key, String data, int ttl) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, data);
            jedis.expire(key, ttl);
            logger.debug("Cache_key_value " + Constants.BASIC_PROFILE_KEY + key + " is saved in redis with ttl=" + ttl + "s");
        } catch (Exception e) {
            logger.error("An Error Occurred while putInBasicProfileCache to Redis", e);
        }
    }

    public String getContentFromCache(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        } catch (Exception e) {
            logger.error("An Error Occurred while getContentFromCache", e);
            return null;
        }
    }

    public boolean deleteCache(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
            logger.debug("Cache_key_value " + key + " is deleted from redis");
            return true;
        } catch (Exception e) {
            logger.error("Failed to evict the cache", e);
            return false;
        }
    }
}
