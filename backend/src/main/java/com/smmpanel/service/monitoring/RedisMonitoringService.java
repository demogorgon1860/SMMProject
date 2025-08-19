package com.smmpanel.service.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Redis Monitoring Service Collects and exposes Redis metrics for monitoring */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisMonitoringService {

    private final RedisConnectionFactory redisConnectionFactory;
    private final MeterRegistry meterRegistry;

    private final AtomicLong connectedClients = new AtomicLong(0);
    private final AtomicLong usedMemory = new AtomicLong(0);
    private final AtomicLong dbSize = new AtomicLong(0);
    private final AtomicLong hitRate = new AtomicLong(0);
    private final AtomicLong missRate = new AtomicLong(0);
    private final AtomicLong evictedKeys = new AtomicLong(0);
    private final AtomicLong expiredKeys = new AtomicLong(0);

    /** Initialize Redis metrics */
    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        // Connected clients gauge
        meterRegistry.gauge(
                "redis.connected.clients", Tags.of("type", "clients"), connectedClients);

        // Memory usage gauge
        meterRegistry.gauge("redis.memory.used.bytes", Tags.of("type", "memory"), usedMemory);

        // Database size gauge
        meterRegistry.gauge("redis.keys.total", Tags.of("type", "keys"), dbSize);

        // Cache hit rate gauge
        meterRegistry.gauge("redis.cache.hit.rate", Tags.of("type", "cache"), hitRate);

        // Cache miss rate gauge
        meterRegistry.gauge("redis.cache.miss.rate", Tags.of("type", "cache"), missRate);

        // Evicted keys gauge
        meterRegistry.gauge("redis.keys.evicted", Tags.of("type", "eviction"), evictedKeys);

        // Expired keys gauge
        meterRegistry.gauge("redis.keys.expired", Tags.of("type", "expiration"), expiredKeys);

        log.info("Redis monitoring metrics initialized");
    }

    /** Collect Redis metrics periodically */
    @Scheduled(fixedDelayString = "${redis.monitoring.interval:30000}") // 30 seconds
    public void collectMetrics() {
        try {
            var connection = redisConnectionFactory.getConnection();

            // Get Redis info
            Properties info = connection.info();
            Properties stats = connection.info("stats");
            Properties memory = connection.info("memory");
            Properties clients = connection.info("clients");

            // Update connected clients
            String connectedClientsStr = clients.getProperty("connected_clients", "0");
            connectedClients.set(Long.parseLong(connectedClientsStr));

            // Update memory usage
            String usedMemoryStr = memory.getProperty("used_memory", "0");
            usedMemory.set(Long.parseLong(usedMemoryStr));

            // Update database size
            Long currentDbSize = connection.dbSize();
            dbSize.set(currentDbSize != null ? currentDbSize : 0);

            // Calculate cache hit/miss rates
            String keyspaceHits = stats.getProperty("keyspace_hits", "0");
            String keyspaceMisses = stats.getProperty("keyspace_misses", "0");
            long hits = Long.parseLong(keyspaceHits);
            long misses = Long.parseLong(keyspaceMisses);
            long total = hits + misses;

            if (total > 0) {
                hitRate.set((hits * 100) / total);
                missRate.set((misses * 100) / total);
            }

            // Update evicted and expired keys
            String evictedKeysStr = stats.getProperty("evicted_keys", "0");
            evictedKeys.set(Long.parseLong(evictedKeysStr));

            String expiredKeysStr = stats.getProperty("expired_keys", "0");
            expiredKeys.set(Long.parseLong(expiredKeysStr));

            connection.close();

            log.debug(
                    "Redis metrics collected - Clients: {}, Memory: {} bytes, Keys: {}",
                    connectedClients.get(),
                    usedMemory.get(),
                    dbSize.get());

        } catch (Exception e) {
            log.error("Failed to collect Redis metrics: {}", e.getMessage());
        }
    }

    /** Get current Redis statistics */
    public RedisStats getCurrentStats() {
        return RedisStats.builder()
                .connectedClients(connectedClients.get())
                .usedMemoryBytes(usedMemory.get())
                .totalKeys(dbSize.get())
                .hitRate(hitRate.get())
                .missRate(missRate.get())
                .evictedKeys(evictedKeys.get())
                .expiredKeys(expiredKeys.get())
                .build();
    }

    /** Redis statistics DTO */
    public static class RedisStats {
        private long connectedClients;
        private long usedMemoryBytes;
        private long totalKeys;
        private long hitRate;
        private long missRate;
        private long evictedKeys;
        private long expiredKeys;

        public RedisStats() {}

        private RedisStats(Builder builder) {
            this.connectedClients = builder.connectedClients;
            this.usedMemoryBytes = builder.usedMemoryBytes;
            this.totalKeys = builder.totalKeys;
            this.hitRate = builder.hitRate;
            this.missRate = builder.missRate;
            this.evictedKeys = builder.evictedKeys;
            this.expiredKeys = builder.expiredKeys;
        }

        public static Builder builder() {
            return new Builder();
        }

        public long getConnectedClients() {
            return connectedClients;
        }

        public long getUsedMemoryBytes() {
            return usedMemoryBytes;
        }

        public long getTotalKeys() {
            return totalKeys;
        }

        public long getHitRate() {
            return hitRate;
        }

        public long getMissRate() {
            return missRate;
        }

        public long getEvictedKeys() {
            return evictedKeys;
        }

        public long getExpiredKeys() {
            return expiredKeys;
        }

        public static class Builder {
            private long connectedClients;
            private long usedMemoryBytes;
            private long totalKeys;
            private long hitRate;
            private long missRate;
            private long evictedKeys;
            private long expiredKeys;

            public Builder connectedClients(long connectedClients) {
                this.connectedClients = connectedClients;
                return this;
            }

            public Builder usedMemoryBytes(long usedMemoryBytes) {
                this.usedMemoryBytes = usedMemoryBytes;
                return this;
            }

            public Builder totalKeys(long totalKeys) {
                this.totalKeys = totalKeys;
                return this;
            }

            public Builder hitRate(long hitRate) {
                this.hitRate = hitRate;
                return this;
            }

            public Builder missRate(long missRate) {
                this.missRate = missRate;
                return this;
            }

            public Builder evictedKeys(long evictedKeys) {
                this.evictedKeys = evictedKeys;
                return this;
            }

            public Builder expiredKeys(long expiredKeys) {
                this.expiredKeys = expiredKeys;
                return this;
            }

            public RedisStats build() {
                return new RedisStats(this);
            }
        }
    }
}
