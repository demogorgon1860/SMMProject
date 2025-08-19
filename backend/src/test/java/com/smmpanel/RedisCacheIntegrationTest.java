package com.smmpanel;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.service.ServiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Integration test for Redis caching Uses TestContainers for Redis instance */
@SpringBootTest
@Testcontainers
public class RedisCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.cache.type", () -> "redis");
    }

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired(required = false)
    private CacheManager cacheManager;

    @Autowired(required = false)
    private ServiceService serviceService;

    @Test
    public void contextLoads() {
        assertNotNull(redisTemplate, "RedisTemplate should be available");
        assertNotNull(cacheManager, "CacheManager should be available");
    }

    @Test
    public void testRedisConnection() {
        if (redisTemplate == null) {
            return; // Skip if not configured
        }

        // Test basic Redis operations
        String key = "test:integration:key";
        String value = "integration-test-value";

        redisTemplate.opsForValue().set(key, value);
        Object retrieved = redisTemplate.opsForValue().get(key);

        assertEquals(value, retrieved);

        // Clean up
        redisTemplate.delete(key);
    }

    @Test
    public void testCacheEviction() {
        if (cacheManager == null) {
            return; // Skip if not configured
        }

        var cache = cacheManager.getCache("services");
        assertNotNull(cache);

        // Add to cache
        cache.put("test-key", "test-value");

        // Verify it's in cache
        var wrapper = cache.get("test-key");
        assertNotNull(wrapper);
        assertEquals("test-value", wrapper.get());

        // Clear cache
        cache.clear();

        // Verify it's gone
        wrapper = cache.get("test-key");
        assertNull(wrapper);
    }
}
