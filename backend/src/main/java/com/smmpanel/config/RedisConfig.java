package com.smmpanel.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheResolver;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Production-Ready Redis Configuration Provides optimized Redis caching with proper serialization
 * and TTL management
 */
@Slf4j
@Configuration
@EnableCaching
@ConditionalOnProperty(value = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisConfig implements CachingConfigurer {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:5000ms}")
    private Duration timeout;

    @Value("${spring.cache.redis.key-prefix:smm:cache:}")
    private String keyPrefix;

    @Value("${spring.cache.redis.use-key-prefix:true}")
    private boolean useKeyPrefix;

    @Value("${spring.cache.redis.cache-null-values:false}")
    private boolean cacheNullValues;

    /**
     * Custom ObjectMapper for Redis serialization Configured with type information for proper
     * deserialization
     */
    @Bean("redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Register JavaTimeModule for Java 8 time types
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Enable type information for polymorphic types
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return mapper;
    }

    /** Redis Client for Bucket4j Rate Limiting */
    @Bean
    public RedisClient redisClient() {
        RedisURI.Builder builder =
                RedisURI.builder().withHost(redisHost).withPort(redisPort).withDatabase(database);

        if (redisPassword != null && !redisPassword.isEmpty()) {
            builder.withPassword(redisPassword.toCharArray());
        }

        RedisURI redisUri = builder.build();
        log.info(
                "Creating RedisClient for rate limiting - host: {}, port: {}, database: {}",
                redisHost,
                redisPort,
                database);
        return RedisClient.create(redisUri);
    }

    /** Redis Connection Factory with optimized settings */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        redisConfig.setDatabase(database);

        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            redisConfig.setPassword(redisPassword);
        }

        // Configure Lettuce client options for production
        SocketOptions socketOptions =
                SocketOptions.builder()
                        .connectTimeout(timeout != null ? timeout : Duration.ofSeconds(5))
                        .keepAlive(true)
                        .tcpNoDelay(true)
                        .build();

        ClientOptions clientOptions =
                ClientOptions.builder()
                        .socketOptions(socketOptions)
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build();

        LettuceClientConfiguration clientConfig =
                LettuceClientConfiguration.builder()
                        .clientOptions(clientOptions)
                        .commandTimeout(timeout != null ? timeout : Duration.ofSeconds(5))
                        .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(redisConfig, clientConfig);
        factory.setValidateConnection(true);

        return factory;
    }

    /** Redis Template for manual Redis operations */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // Use JSON serializer for values with custom ObjectMapper
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.setEnableTransactionSupport(false);
        template.afterPropertiesSet();

        return template;
    }

    /** Cache Manager with comprehensive cache configurations */
    @Bean
    @Primary
    @Override
    public CacheManager cacheManager() {
        RedisConnectionFactory connectionFactory = redisConnectionFactory();

        // Create JSON serializer with custom ObjectMapper
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        // Default cache configuration
        RedisCacheConfiguration defaultConfig =
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(Duration.ofMinutes(30))
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(
                                        new StringRedisSerializer()))
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(
                                        jsonSerializer))
                        .disableCachingNullValues();

        if (useKeyPrefix) {
            defaultConfig = defaultConfig.prefixCacheNameWith(keyPrefix);
        }

        if (!cacheNullValues) {
            defaultConfig = defaultConfig.disableCachingNullValues();
        }

        // Individual cache configurations with specific TTLs
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Service-related caches
        cacheConfigurations.put("services", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("active-services", defaultConfig.entryTtl(Duration.ofHours(1)));

        // User-related caches
        cacheConfigurations.put("users", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("balance", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("user-balances", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // YouTube-related caches
        cacheConfigurations.put("youtube-views", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("youtube-stats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("youtube-accounts", defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Order and campaign caches
        cacheConfigurations.put("start_count", defaultConfig.entryTtl(Duration.ofDays(30)));
        cacheConfigurations.put("assignedCampaigns", defaultConfig.entryTtl(Duration.ofHours(2)));

        // Binom-related caches
        cacheConfigurations.put("binom-campaigns", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("binom-offers", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("binom-statistics", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("binom-landing-pages", defaultConfig.entryTtl(Duration.ofHours(2)));

        log.info(
                "Initializing Redis Cache Manager with {} cache configurations",
                cacheConfigurations.size());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    /** Custom cache error handler for resilience */
    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler();
    }

    /** Custom key generator */
    @Override
    public KeyGenerator keyGenerator() {
        return new SimpleKeyGenerator();
    }

    /** Cache resolver */
    @Override
    public CacheResolver cacheResolver() {
        return new SimpleCacheResolver(cacheManager());
    }

    /**
     * Custom error handler that logs errors but doesn't throw exceptions This ensures the
     * application continues to work even if Redis is down
     */
    private static class RedisCacheErrorHandler extends SimpleCacheErrorHandler {

        @Override
        public void handleCacheGetError(
                RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
            log.error(
                    "Redis cache GET error - cache: {}, key: {}, error: {}",
                    cache.getName(),
                    key,
                    exception.getMessage());
        }

        @Override
        public void handleCachePutError(
                RuntimeException exception,
                org.springframework.cache.Cache cache,
                Object key,
                Object value) {
            log.error(
                    "Redis cache PUT error - cache: {}, key: {}, error: {}",
                    cache.getName(),
                    key,
                    exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(
                RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
            log.error(
                    "Redis cache EVICT error - cache: {}, key: {}, error: {}",
                    cache.getName(),
                    key,
                    exception.getMessage());
        }

        @Override
        public void handleCacheClearError(
                RuntimeException exception, org.springframework.cache.Cache cache) {
            log.error(
                    "Redis cache CLEAR error - cache: {}, error: {}",
                    cache.getName(),
                    exception.getMessage());
        }
    }
}
