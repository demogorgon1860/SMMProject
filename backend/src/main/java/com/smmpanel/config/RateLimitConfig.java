package com.smmpanel.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Supplier;

@Configuration
public class RateLimitConfig {
    
    private final RedisProperties redisProperties;
    
    public RateLimitConfig(RedisProperties redisProperties) {
        this.redisProperties = redisProperties;
    }
    
    @Bean
    public ProxyManager<String> proxyManager() {
        RedisClient client = RedisClient.create("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort());
        return LettuceBasedProxyManager.builderFor(client).build();
    }
    
    @Bean
    public Supplier<BucketConfiguration> rateLimitConfig() {
        Refill refill = Refill.intervally(100, Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(100, refill);
        return () -> BucketConfiguration.builder()
                .addLimit(limit)
                .build();
    }
    
    @Bean
    public Bucket bucket(ProxyManager<String> proxyManager, Supplier<BucketConfiguration> config) {
        return proxyManager.builder().build("rate-limit-bucket", config);
    }
}
