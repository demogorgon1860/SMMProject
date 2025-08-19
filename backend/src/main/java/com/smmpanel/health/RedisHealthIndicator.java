package com.smmpanel.health;

import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/** Redis Health Indicator for monitoring Redis connectivity and performance */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisConnectionFactory connectionFactory;

    @Override
    public Health health() {
        try {
            log.debug("Checking Redis health...");

            // Test basic connectivity with PING
            String pingResult =
                    redisTemplate.execute(
                            (RedisConnection connection) -> {
                                return connection.ping();
                            },
                            true);

            if (!"PONG".equals(pingResult)) {
                return Health.down()
                        .withDetail("message", "Redis ping failed")
                        .withDetail("ping_response", pingResult)
                        .build();
            }

            // Get Redis server info
            Properties info =
                    redisTemplate.execute(
                            (RedisConnection connection) -> {
                                return connection.info();
                            },
                            true);

            // Extract key metrics
            String redisVersion =
                    info != null ? info.getProperty("redis_version", "unknown") : "unknown";
            String usedMemory =
                    info != null ? info.getProperty("used_memory_human", "unknown") : "unknown";
            String connectedClients =
                    info != null ? info.getProperty("connected_clients", "0") : "0";
            String uptime = info != null ? info.getProperty("uptime_in_seconds", "0") : "0";

            // Test write and read operations
            String testKey = "health:check:" + System.currentTimeMillis();
            String testValue = "healthy";

            redisTemplate.opsForValue().set(testKey, testValue);
            Object retrieved = redisTemplate.opsForValue().get(testKey);
            redisTemplate.delete(testKey);

            if (!testValue.equals(retrieved)) {
                return Health.down()
                        .withDetail("message", "Redis read/write test failed")
                        .withDetail("expected", testValue)
                        .withDetail("actual", retrieved)
                        .build();
            }

            // Check cache statistics
            long dbSize =
                    redisTemplate.execute(
                            (RedisConnection connection) -> {
                                return connection.dbSize();
                            },
                            true);

            log.info(
                    "Redis health check successful - Version: {}, Memory: {}, Clients: {}, Keys:"
                            + " {}",
                    redisVersion,
                    usedMemory,
                    connectedClients,
                    dbSize);

            return Health.up()
                    .withDetail("message", "Redis is healthy")
                    .withDetail("version", redisVersion)
                    .withDetail("used_memory", usedMemory)
                    .withDetail("connected_clients", connectedClients)
                    .withDetail("uptime_seconds", uptime)
                    .withDetail("db_size", dbSize)
                    .withDetail("connection_factory", connectionFactory.getClass().getSimpleName())
                    .build();

        } catch (Exception e) {
            log.error("Redis health check failed: {}", e.getMessage(), e);
            return Health.down()
                    .withDetail("message", "Redis connection failed")
                    .withDetail("error", e.getMessage())
                    .withDetail("error_type", e.getClass().getSimpleName())
                    .build();
        }
    }
}
