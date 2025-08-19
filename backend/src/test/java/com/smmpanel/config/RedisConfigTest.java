package com.smmpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public class RedisConfigTest {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private CacheManager cacheManager;

    @Test
    public void testRedisTemplateConfiguration() {
        assertNotNull(redisTemplate, "RedisTemplate should be configured");

        // Test basic operations
        String key = "test:key";
        String value = "test-value";

        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(60));
        Object retrieved = redisTemplate.opsForValue().get(key);

        assertEquals(value, retrieved, "Value should be stored and retrieved correctly");

        // Clean up
        redisTemplate.delete(key);
    }

    @Test
    public void testCacheManagerConfiguration() {
        assertNotNull(cacheManager, "CacheManager should be configured");

        // Check that cache configurations exist
        assertTrue(cacheManager.getCacheNames().contains("services"));
        assertTrue(cacheManager.getCacheNames().contains("users"));
        assertTrue(cacheManager.getCacheNames().contains("exchangeRates"));
        assertTrue(cacheManager.getCacheNames().contains("youtube-views"));
    }

    @Test
    public void testCacheOperations() {
        if (cacheManager == null) {
            return; // Skip if not configured
        }

        var cache = cacheManager.getCache("services");
        assertNotNull(cache, "Services cache should exist");

        // Test cache put and get
        String key = "test-service";
        String value = "test-service-data";

        cache.put(key, value);
        var wrapper = cache.get(key);

        assertNotNull(wrapper, "Cache should contain the key");
        assertEquals(value, wrapper.get(), "Cache should return correct value");

        // Test cache evict
        cache.evict(key);
        wrapper = cache.get(key);
        assertNull(wrapper, "Cache should not contain evicted key");
    }
}
