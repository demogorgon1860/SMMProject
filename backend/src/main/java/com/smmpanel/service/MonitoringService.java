package com.smmpanel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuator.health.Health;
import org.springframework.boot.actuator.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitoringService implements HealthIndicator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final SeleniumService seleniumService;

    @Value("${app.monitoring.enabled:true}")
    private boolean monitoringEnabled;

    private final Map<String, Boolean> serviceHealth = new HashMap<>();
    private LocalDateTime lastHealthCheck = LocalDateTime.now();

    @Override
    public Health health() {
        if (!monitoringEnabled) {
            return Health.up().withDetail("monitoring", "disabled").build();
        }

        Health.Builder builder = Health.up();
        
        // Check if recent health check exists
        if (Duration.between(lastHealthCheck, LocalDateTime.now()).toMinutes() > 5) {
            performHealthChecks();
        }

        // Add service statuses
        serviceHealth.forEach((service, healthy) -> {
            builder.withDetail(service, healthy ? "UP" : "DOWN");
        });

        // Overall health
        boolean allHealthy = serviceHealth.values().stream().allMatch(Boolean::booleanValue);
        
        return allHealthy ? builder.build() : Health.down().withDetails(serviceHealth).build();
    }

    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void performHealthChecks() {
        if (!monitoringEnabled) {
            return;
        }

        log.debug("Performing health checks...");

        // Check Redis
        checkRedis();
        
        // Check Kafka
        checkKafka();
        
        // Check Selenium
        checkSelenium();

        lastHealthCheck = LocalDateTime.now();
        
        log.debug("Health checks completed. Status: {}", serviceHealth);
    }

    private void checkRedis() {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                redisTemplate.opsForValue().set("health-check", "ok");
                String result = (String) redisTemplate.opsForValue().get("health-check");
                if (!"ok".equals(result)) {
                    throw new RuntimeException("Redis health check failed");
                }
            });

            future.get(5, TimeUnit.SECONDS);
            serviceHealth.put("redis", true);
            log.debug("Redis health check: OK");

        } catch (Exception e) {
            serviceHealth.put("redis", false);
            log.warn("Redis health check failed: {}", e.getMessage());
        }
    }

    private void checkKafka() {
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                kafkaTemplate.send("health-check", "ping");
            });

            future.get(10, TimeUnit.SECONDS);
            serviceHealth.put("kafka", true);
            log.debug("Kafka health check: OK");

        } catch (Exception e) {
            serviceHealth.put("kafka", false);
            log.warn("Kafka health check failed: {}", e.getMessage());
        }
    }

    private void checkSelenium() {
        try {
            boolean seleniumHealthy = seleniumService.testConnection();
            serviceHealth.put("selenium", seleniumHealthy);
            log.debug("Selenium health check: {}", seleniumHealthy ? "OK" : "FAILED");

        } catch (Exception e) {
            serviceHealth.put("selenium", false);
            log.warn("Selenium health check failed: {}", e.getMessage());
        }
    }

    public Map<String, Boolean> getServiceHealth() {
        return new HashMap<>(serviceHealth);
    }

    public boolean isHealthy() {
        return serviceHealth.values().stream().allMatch(Boolean::booleanValue);
    }

    public void forceHealthCheck() {
        performHealthChecks();
    }
}