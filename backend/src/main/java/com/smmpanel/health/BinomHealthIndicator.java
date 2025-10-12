package com.smmpanel.health;

import com.smmpanel.client.BinomClient;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Health indicator for Binom API integration Monitors API connectivity and response times */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinomHealthIndicator implements HealthIndicator {

    private final BinomClient binomClient;
    private LocalDateTime lastSuccessfulCheck = LocalDateTime.now();
    private long lastResponseTime = 0;

    @Override
    public Health health() {
        try {
            // Perform health check with timeout
            long startTime = System.currentTimeMillis();

            CompletableFuture<Boolean> healthCheck =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    // Try to get campaigns list as health check
                                    // Pass empty list for geo filter to get all campaigns
                                    var campaigns = binomClient.getCampaigns(new ArrayList<>());
                                    return campaigns != null;
                                } catch (Exception e) {
                                    log.warn("Binom health check failed: {}", e.getMessage());
                                    return false;
                                }
                            });

            Boolean isHealthy = healthCheck.get(5, TimeUnit.SECONDS);
            lastResponseTime = System.currentTimeMillis() - startTime;

            if (isHealthy) {
                lastSuccessfulCheck = LocalDateTime.now();
                return Health.up()
                        .withDetail("status", "Connected")
                        .withDetail("responseTime", lastResponseTime + "ms")
                        .withDetail("lastSuccessfulCheck", lastSuccessfulCheck)
                        .build();
            } else {
                return Health.down()
                        .withDetail("status", "API returned error")
                        .withDetail("lastResponseTime", lastResponseTime + "ms")
                        .withDetail("lastSuccessfulCheck", lastSuccessfulCheck)
                        .build();
            }

        } catch (Exception e) {
            log.error("Binom health check error: {}", e.getMessage());

            // Check if it's been too long since last successful check
            Duration timeSinceLastSuccess =
                    Duration.between(lastSuccessfulCheck, LocalDateTime.now());
            boolean isCritical = timeSinceLastSuccess.toMinutes() > 10;

            return Health.down()
                    .withDetail("status", "Connection failed")
                    .withDetail("error", e.getMessage())
                    .withDetail("lastSuccessfulCheck", lastSuccessfulCheck)
                    .withDetail("criticalStatus", isCritical ? "CRITICAL" : "WARNING")
                    .build();
        }
    }
}
