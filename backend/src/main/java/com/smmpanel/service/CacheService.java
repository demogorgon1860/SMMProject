package com.smmpanel.service;

import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Cache Service Manages Redis caching operations with proper TTL management */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    /** Cache service list with 1 hour TTL */
    @Cacheable(value = "services", key = "'all'")
    public Object cacheServices(Object services) {
        log.debug("Caching services list");
        return services;
    }

    /** Cache start count for order with 30 days TTL */
    @CachePut(value = "start_count", key = "#orderId")
    public Integer cacheStartCount(Long orderId, Integer count) {
        log.debug("Caching start count for order: {} = {}", orderId, count);
        return count;
    }

    /** Get cached start count */
    @Cacheable(value = "start_count", key = "#orderId")
    public Integer getCachedStartCount(Long orderId) {
        log.debug("Cache miss for start count: {}", orderId);
        return null; // Will be populated by caller
    }

    /** Cache user balance with 5 minutes TTL */
    @CachePut(value = "balance", key = "#userId")
    public Object cacheBalance(Long userId, Object balance) {
        log.debug("Caching balance for user: {}", userId);
        return balance;
    }

    /** Get cached balance */
    @Cacheable(value = "balance", key = "#userId")
    public Object getCachedBalance(Long userId) {
        log.debug("Cache miss for balance: {}", userId);
        return null; // Will be populated by caller
    }

    /** Evict balance cache when balance changes */
    @CacheEvict(value = "balance", key = "#userId")
    public void evictBalance(Long userId) {
        log.debug("Evicting balance cache for user: {}", userId);
    }

    /** Cache YouTube view count with 5 minutes TTL */
    @CachePut(value = "youtube-views", key = "#videoId")
    public Long cacheYouTubeViews(String videoId, Long viewCount) {
        log.debug("Caching YouTube views for video: {} = {}", videoId, viewCount);
        return viewCount;
    }

    /** Get cached YouTube view count */
    @Cacheable(value = "youtube-views", key = "#videoId")
    public Long getCachedYouTubeViews(String videoId) {
        log.debug("Cache miss for YouTube views: {}", videoId);
        return null; // Will be populated by caller
    }

    /** Manual cache operations for fine-grained control */
    public void setWithTtl(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
            log.debug("Set cache with TTL: key={}, ttl={}s", key, ttlSeconds);
        } catch (Exception e) {
            log.warn("Failed to set cache: key={}", key, e);
        }
    }

    public Object get(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            log.debug("Get cache: key={}, hit={}", key, value != null);
            return value;
        } catch (Exception e) {
            log.warn("Failed to get cache: key={}", key, e);
            return null;
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
            log.debug("Delete cache: key={}", key);
        } catch (Exception e) {
            log.warn("Failed to delete cache: key={}", key, e);
        }
    }

    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            log.debug("Check cache exists: key={}, exists={}", key, exists);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Failed to check cache exists: key={}", key, e);
            return false;
        }
    }

    /** Clear all caches */
    public void clearAllCaches() {
        try {
            redisTemplate.getConnectionFactory().getConnection().flushDb();
            log.info("Cleared all caches");
        } catch (Exception e) {
            log.error("Failed to clear all caches", e);
        }
    }

    /** Get cache statistics */
    public CacheStats getCacheStats() {
        try {
            Long dbSize = redisTemplate.getConnectionFactory().getConnection().dbSize();
            return CacheStats.builder().totalKeys(dbSize).build();
        } catch (Exception e) {
            log.warn("Failed to get cache stats", e);
            return CacheStats.builder().totalKeys(0L).build();
        }
    }

    /** Cache Statistics DTO */
    public static class CacheStats {
        private Long totalKeys;

        public CacheStats() {}

        public CacheStats(Long totalKeys) {
            this.totalKeys = totalKeys;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Long getTotalKeys() {
            return totalKeys;
        }

        public void setTotalKeys(Long totalKeys) {
            this.totalKeys = totalKeys;
        }

        public static class Builder {
            private Long totalKeys;

            public Builder totalKeys(Long totalKeys) {
                this.totalKeys = totalKeys;
                return this;
            }

            public CacheStats build() {
                return new CacheStats(totalKeys);
            }
        }
    }
}
