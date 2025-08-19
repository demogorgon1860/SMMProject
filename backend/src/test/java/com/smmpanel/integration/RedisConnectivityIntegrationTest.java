package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Slf4j
@SpringBootTest
@Testcontainers
public class RedisConnectivityIntegrationTest {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--appendonly", "yes");

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.cache.redis.time-to-live", () -> "60000");
    }

    @Autowired private RedisTemplate<String, Object> redisTemplate;

    @Autowired private StringRedisTemplate stringRedisTemplate;

    @Autowired private CacheManager cacheManager;

    @Test
    public void testRedisConnection() {
        log.info("Testing Redis connectivity...");

        assertNotNull(redisTemplate, "RedisTemplate should be initialized");
        assertNotNull(stringRedisTemplate, "StringRedisTemplate should be initialized");

        String pingResult = stringRedisTemplate.execute(connection -> connection.ping(), true);
        assertEquals("PONG", pingResult, "Redis should respond to PING");

        log.info("Successfully connected to Redis");
    }

    @Test
    public void testRedisStringOperations() {
        log.info("Testing Redis string operations...");

        String key = "test:string:" + UUID.randomUUID();
        String value = "test-value-" + System.currentTimeMillis();

        stringRedisTemplate.opsForValue().set(key, value);
        String retrieved = stringRedisTemplate.opsForValue().get(key);

        assertEquals(value, retrieved, "Retrieved value should match stored value");
        log.info("Successfully stored and retrieved string value: {}", retrieved);

        stringRedisTemplate.opsForValue().set(key, value, Duration.ofSeconds(5));
        assertTrue(stringRedisTemplate.hasKey(key), "Key should exist");

        stringRedisTemplate.delete(key);
        assertFalse(stringRedisTemplate.hasKey(key), "Key should be deleted");

        log.info("String operations test completed successfully");
    }

    @Test
    public void testRedisHashOperations() {
        log.info("Testing Redis hash operations...");

        String hashKey = "test:hash:" + UUID.randomUUID();
        Map<String, String> data =
                Map.of(
                        "field1", "value1",
                        "field2", "value2",
                        "field3", "value3");

        stringRedisTemplate.opsForHash().putAll(hashKey, data);

        String field1Value = (String) stringRedisTemplate.opsForHash().get(hashKey, "field1");
        assertEquals("value1", field1Value, "Hash field value should match");

        Map<Object, Object> allEntries = stringRedisTemplate.opsForHash().entries(hashKey);
        assertEquals(3, allEntries.size(), "Should have 3 hash entries");

        log.info("Successfully performed hash operations with {} entries", allEntries.size());

        stringRedisTemplate.delete(hashKey);
    }

    @Test
    public void testRedisSetOperations() {
        log.info("Testing Redis set operations...");

        String setKey = "test:set:" + UUID.randomUUID();

        stringRedisTemplate.opsForSet().add(setKey, "member1", "member2", "member3");

        Long size = stringRedisTemplate.opsForSet().size(setKey);
        assertEquals(3L, size, "Set should have 3 members");

        Boolean isMember = stringRedisTemplate.opsForSet().isMember(setKey, "member2");
        assertTrue(isMember, "member2 should be in the set");

        Set<String> members = stringRedisTemplate.opsForSet().members(setKey);
        assertNotNull(members);
        assertEquals(3, members.size(), "Should retrieve all 3 members");

        log.info("Successfully performed set operations with members: {}", members);

        stringRedisTemplate.delete(setKey);
    }

    @Test
    public void testRedisListOperations() {
        log.info("Testing Redis list operations...");

        String listKey = "test:list:" + UUID.randomUUID();

        stringRedisTemplate.opsForList().rightPushAll(listKey, "item1", "item2", "item3");

        Long size = stringRedisTemplate.opsForList().size(listKey);
        assertEquals(3L, size, "List should have 3 items");

        String firstItem = stringRedisTemplate.opsForList().index(listKey, 0);
        assertEquals("item1", firstItem, "First item should be item1");

        String poppedItem = stringRedisTemplate.opsForList().leftPop(listKey);
        assertEquals("item1", poppedItem, "Popped item should be item1");

        size = stringRedisTemplate.opsForList().size(listKey);
        assertEquals(2L, size, "List should have 2 items after pop");

        log.info("Successfully performed list operations");

        stringRedisTemplate.delete(listKey);
    }

    @Test
    public void testRedisCacheOperations() {
        log.info("Testing Redis cache operations...");

        assertNotNull(cacheManager, "CacheManager should be initialized");

        Cache servicesCache = cacheManager.getCache("services");
        assertNotNull(servicesCache, "Services cache should exist");

        Service testService = new Service();
        testService.setId(999L);
        testService.setName("Test Service");
        testService.setCategory("TEST");
        testService.setPricePer1000(new BigDecimal("0.001"));
        testService.setMinOrder(1);
        testService.setMaxOrder(10000);
        testService.setActive(true);

        servicesCache.put("test-service-999", testService);

        Cache.ValueWrapper wrapper = servicesCache.get("test-service-999");
        assertNotNull(wrapper, "Should retrieve cached value");

        Service cachedService = (Service) wrapper.get();
        assertNotNull(cachedService, "Cached service should not be null");
        assertEquals(999L, cachedService.getId(), "Service ID should match");
        assertEquals("Test Service", cachedService.getName(), "Service name should match");

        log.info("Successfully cached and retrieved service: {}", cachedService.getName());

        servicesCache.evict("test-service-999");
        wrapper = servicesCache.get("test-service-999");
        assertNull(wrapper, "Value should be evicted from cache");

        log.info("Cache operations test completed successfully");
    }

    @Test
    public void testRedisExpiration() throws InterruptedException {
        log.info("Testing Redis key expiration...");

        String key = "test:expiring:" + UUID.randomUUID();
        String value = "will-expire";

        stringRedisTemplate.opsForValue().set(key, value, 2, TimeUnit.SECONDS);

        String retrieved = stringRedisTemplate.opsForValue().get(key);
        assertEquals(value, retrieved, "Value should be retrievable immediately");

        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2, "TTL should be between 0 and 2 seconds");

        log.info("Key TTL: {} seconds", ttl);

        Thread.sleep(3000);

        retrieved = stringRedisTemplate.opsForValue().get(key);
        assertNull(retrieved, "Value should have expired");

        log.info("Expiration test completed successfully");
    }

    @Test
    public void testRedisTransactions() {
        log.info("Testing Redis transactions...");

        String key1 = "test:tx:1:" + UUID.randomUUID();
        String key2 = "test:tx:2:" + UUID.randomUUID();

        stringRedisTemplate.execute(
                connection -> {
                    connection.multi();
                    connection.stringCommands().set(key1.getBytes(), "value1".getBytes());
                    connection.stringCommands().set(key2.getBytes(), "value2".getBytes());
                    connection.exec();
                    return null;
                },
                true);

        String value1 = stringRedisTemplate.opsForValue().get(key1);
        String value2 = stringRedisTemplate.opsForValue().get(key2);

        assertEquals("value1", value1, "First transaction value should be set");
        assertEquals("value2", value2, "Second transaction value should be set");

        log.info("Successfully executed Redis transaction");

        stringRedisTemplate.delete(key1);
        stringRedisTemplate.delete(key2);
    }

    @Test
    public void testRedisConversionCoefficientCache() {
        log.info("Testing conversion coefficient caching...");

        Cache conversionCache = cacheManager.getCache("conversionCoefficients");
        assertNotNull(conversionCache, "Conversion coefficients cache should exist");

        Double coefficient = 1.5;
        String cacheKey = "USD_EUR";

        conversionCache.put(cacheKey, coefficient);

        Cache.ValueWrapper wrapper = conversionCache.get(cacheKey);
        assertNotNull(wrapper, "Should retrieve cached coefficient");

        Double cachedCoefficient = (Double) wrapper.get();
        assertEquals(coefficient, cachedCoefficient, "Cached coefficient should match");

        log.info(
                "Successfully cached conversion coefficient: {} = {}", cacheKey, cachedCoefficient);

        conversionCache.clear();
        log.info("Conversion coefficient cache test completed");
    }

    @Test
    public void testRedisUserCache() {
        log.info("Testing user caching...");

        Cache userCache = cacheManager.getCache("users");
        assertNotNull(userCache, "Users cache should exist");

        User testUser = new User();
        testUser.setId(999L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");

        userCache.put("user-999", testUser);

        Cache.ValueWrapper wrapper = userCache.get("user-999");
        assertNotNull(wrapper, "Should retrieve cached user");

        User cachedUser = (User) wrapper.get();
        assertNotNull(cachedUser, "Cached user should not be null");
        assertEquals("testuser", cachedUser.getUsername(), "Username should match");

        log.info("Successfully cached and retrieved user: {}", cachedUser.getUsername());

        userCache.evict("user-999");
        log.info("User cache test completed");
    }
}
