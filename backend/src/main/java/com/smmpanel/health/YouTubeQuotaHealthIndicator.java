package com.smmpanel.health;

import com.smmpanel.service.integration.YouTubeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health indicator for YouTube API quota usage Monitors quota consumption and warns when
 * approaching limits
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubeQuotaHealthIndicator implements HealthIndicator {

    private final YouTubeService youTubeService;

    @Override
    public Health health() {
        try {
            double quotaUsagePercentage = youTubeService.getQuotaUsagePercentage();

            Health.Builder builder;
            String status;

            if (quotaUsagePercentage < 50) {
                builder = Health.up();
                status = "HEALTHY";
            } else if (quotaUsagePercentage < 80) {
                builder = Health.up();
                status = "WARNING";
            } else if (quotaUsagePercentage < 95) {
                // Use custom status for high usage
                builder = Health.status(new Status("WARNING"));
                status = "HIGH_USAGE";
            } else {
                builder = Health.down();
                status = "CRITICAL";
            }

            return builder.withDetail("quotaUsage", String.format("%.2f%%", quotaUsagePercentage))
                    .withDetail("status", status)
                    .withDetail("recommendation", getRecommendation(quotaUsagePercentage))
                    .build();

        } catch (Exception e) {
            log.error("Error checking YouTube quota health: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("status", "ERROR")
                    .build();
        }
    }

    private String getRecommendation(double usage) {
        if (usage < 50) {
            return "Quota usage is healthy";
        } else if (usage < 80) {
            return "Monitor quota usage, consider caching more aggressively";
        } else if (usage < 95) {
            return "High quota usage - enable aggressive caching and consider rate limiting";
        } else {
            return "Critical quota usage - API calls may fail, immediate action required";
        }
    }
}
