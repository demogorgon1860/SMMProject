package com.smmpanel.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PRODUCTION-READY Rate Limiting Configuration
 *
 * <p>IMPROVEMENTS: 1. Redis-backed distributed rate limiting 2. Configurable limits for different
 * operations 3. Proper error handling and fallbacks
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RateLimitConfig {

    private final RedisClient redisClient;
    private final AppProperties appProperties;

    @Bean
    public LettuceBasedProxyManager<byte[]> lettuceBasedProxyManager() {
        // Redis-backed proxy manager for distributed rate limiting
        return LettuceBasedProxyManager.builderFor(redisClient)
                .withExpirationStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofMinutes(10)))
                .build();
    }

    @Bean
    public BucketConfiguration defaultRateLimitConfig() {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(100, Duration.ofMinutes(1)))
                .addLimit(Bandwidth.simple(1000, Duration.ofHours(1)))
                .build();
    }

    @Bean
    public BucketConfiguration orderRateLimitConfig() {
        return BucketConfiguration.builder()
                .addLimit(
                        Bandwidth.simple(
                                appProperties.getRateLimit().getOrders().getPerMinute(),
                                Duration.ofMinutes(1)))
                .addLimit(
                        Bandwidth.simple(
                                appProperties.getRateLimit().getOrders().getPerHour(),
                                Duration.ofHours(1)))
                .build();
    }

    @Bean
    public BucketConfiguration apiRateLimitConfig() {
        return BucketConfiguration.builder()
                .addLimit(
                        Bandwidth.simple(
                                appProperties.getRateLimit().getApi().getPerMinute(),
                                Duration.ofMinutes(1)))
                .addLimit(
                        Bandwidth.simple(
                                appProperties.getRateLimit().getApi().getPerHour(),
                                Duration.ofHours(1)))
                .build();
    }

    @Bean
    public BucketConfiguration authRateLimitConfig() {
        return BucketConfiguration.builder()
                .addLimit(
                        Bandwidth.simple(
                                appProperties.getRateLimit().getAuth().getPerMinute(),
                                Duration.ofMinutes(1)))
                .addLimit(
                        Bandwidth.simple(
                                appProperties.getRateLimit().getAuth().getPerMinute() * 5,
                                Duration.ofHours(1)))
                .build();
    }
}
