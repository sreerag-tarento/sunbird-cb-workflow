package org.sunbird.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
@Component
public class WorkflowRedisCacheMgr {

        @Autowired
        @Qualifier("jedisWorkflowPopulationPool")
        private JedisPool jedisPool;

    private final Logger logger = LoggerFactory.getLogger(WorkflowRedisCacheMgr.class);

        public void put(String key, String value, int ttl) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(key, value);
                jedis.expire(key, ttl);
                logger.debug("Cache_key_value " + key + " is saved in redis");
            } catch (Exception e) {
                logger.error("An error occurred while saving data into Redis",e);
            }
        }

        public String get(String key) {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(key);
            } catch (Exception e) {
                logger.error("An Error Occurred while getting content from cache", e);
                return null;
            }
        }
}
