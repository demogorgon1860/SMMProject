package com.smmpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test Configuration for SMM Panel
 * Provides test-specific beans and configurations to ensure proper test execution
 */
@TestConfiguration
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, brokerProperties = {"listeners=PLAINTEXT://localhost:9092", "port=9092"})
public class TestConfig {

    /**
     * Provides a test-specific ObjectMapper with proper date/time handling
     */
    @Bean
    @Primary
    public ObjectMapper testObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Provides a RestTemplate bean for tests
     * This is required by ExchangeRateService and other services
     */
    @Bean
    @Primary
    public RestTemplate testRestTemplate() {
        return new RestTemplate();
    }

    /**
     * Provides a test-specific CacheManager
     * Uses in-memory cache for tests instead of Redis
     */
    @Bean
    @Primary
    public CacheManager testCacheManager() {
        return new ConcurrentMapCacheManager("exchangeRates", "currencies", "userPreferences");
    }

    /**
     * Provides a test-specific Redis connection factory
     * Uses embedded Redis or mock for tests
     */
    @Bean
    @Primary
    public RedisConnectionFactory testRedisConnectionFactory() {
        return new LettuceConnectionFactory("localhost", 6379);
    }

    /**
     * Provides a test-specific Redis template
     */
    @Bean
    @Primary
    public RedisTemplate<String, Object> testRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
} 