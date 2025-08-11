package com.smmpanel.config.fraud;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.order.fraud.detection")
public class FraudDetectionProperties {

    /** Whether fraud detection is enabled */
    private boolean enabled = true;

    /** Rate limiting configuration */
    private final RateLimit rateLimit = new RateLimit();

    /** Duplicate detection configuration */
    private final DuplicateDetection duplicateDetection = new DuplicateDetection();

    /** Suspicious patterns configuration */
    private final SuspiciousPatterns suspiciousPatterns = new SuspiciousPatterns();

    /** User verification configuration */
    private final UserVerification userVerification = new UserVerification();

    @Getter
    @Setter
    public static class RateLimit {
        /** Whether rate limiting is enabled */
        private boolean enabled = true;

        /** Maximum number of requests per minute */
        @Min(1)
        private int requestsPerMinute = 30;

        /** Maximum capacity of the rate limit bucket */
        @Min(1)
        private int bucketCapacity = 30;

        /** Number of tokens to refill */
        @Min(1)
        private int refillTokens = 30;

        /** Duration between refills in minutes */
        @Min(1)
        private int refillDurationMinutes = 1;
    }

    @Getter
    @Setter
    public static class DuplicateDetection {
        /** Whether duplicate detection is enabled */
        private boolean enabled = true;

        /** Time window in minutes to check for duplicate orders */
        @Min(1)
        private int timeWindowMinutes = 60;

        /** Maximum number of duplicate order attempts allowed within the time window */
        @Min(1)
        private int maxDuplicateAttempts = 3;
    }

    @Getter
    @Setter
    public static class SuspiciousPatterns {
        /** Whether suspicious pattern detection is enabled */
        private boolean enabled = true;

        /** Maximum number of orders allowed per hour per user */
        @Min(1)
        private int maxOrdersPerHour = 10;

        /** Maximum percentage of orders that can have the same quantity before being flagged */
        @Min(1)
        @Max(100)
        private int maxSameQuantityPercent = 60;

        /** Threshold for high-value orders (in the default currency) */
        @Min(0)
        private double highValueThreshold = 100.0;
    }

    @Getter
    @Setter
    public static class UserVerification {
        /** Whether user verification is enabled */
        private boolean enabled = true;

        /** Minimum account age in days before placing orders */
        @Min(0)
        private int minAccountAgeDays = 1;

        /** Minimum number of successful orders before high-value orders are allowed */
        @Min(0)
        private int minSuccessfulOrders = 1;
    }
}
