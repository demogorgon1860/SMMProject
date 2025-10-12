package com.smmpanel.service.core;

import com.smmpanel.entity.User;
import com.smmpanel.exception.RateLimitExceededException;
import com.smmpanel.service.user.UserService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * PRODUCTION-READY Rate Limiting Service using Redis-backed Bucket4j
 *
 * <p>IMPROVEMENTS: 1. Redis-backed distributed rate limiting 2. Multiple rate limit policies for
 * different operations 3. User-specific and global rate limits 4. Configurable limits via
 * properties 5. Proper error handling and logging
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisClient redisClient;
    private final LettuceBasedProxyManager<byte[]> lettuceBasedProxyManager;

    @Autowired @Lazy // Add this to break circular dependency
    private UserService userService;

    // Cache bucket configurations to avoid recreation
    private final Map<String, BucketConfiguration> configCache = new ConcurrentHashMap<>();

    @Value("${app.rate-limit.orders.per-minute:10}")
    private int ordersPerMinute;

    @Value("${app.rate-limit.orders.per-hour:100}")
    private int ordersPerHour;

    @Value("${app.rate-limit.api.per-minute:60}")
    private int apiCallsPerMinute;

    @Value("${app.rate-limit.api.per-hour:1000}")
    private int apiCallsPerHour;

    @Value("${app.rate-limit.auth.per-minute:5}")
    private int authAttemptsPerMinute;

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    /** Check rate limit for a specific operation */
    public void checkRateLimit(String userId, String operation) {
        if (!rateLimitEnabled) {
            return;
        }

        // Admin users bypass rate limiting
        try {
            Long userIdLong = Long.parseLong(userId);
            Optional<User> userOpt = userService.getUserWithVersion(userIdLong);
            if (userOpt.isPresent() && userOpt.get().isAdmin()) {
                log.debug("Admin user {} bypassing rate limit for operation {}", userId, operation);
                return;
            }
        } catch (NumberFormatException e) {
            // userId is not a number (e.g., IP address), continue with rate limiting
            log.debug("Non-numeric userId {}, applying rate limits", userId);
        }

        String bucketKey = generateBucketKey(userId, operation);
        BucketConfiguration config = getConfigurationForOperation(operation);

        Bucket bucket =
                lettuceBasedProxyManager.builder().build(bucketKey.getBytes(), () -> config);

        if (!bucket.tryConsume(1)) {
            log.warn("Rate limit exceeded for user {} on operation {}", userId, operation);
            throw new RateLimitExceededException(
                    String.format(
                            "Rate limit exceeded for operation '%s'. Please try again later.",
                            operation));
        }

        log.debug("Rate limit check passed for user {} on operation {}", userId, operation);
    }

    /** Check rate limit and return remaining tokens */
    public RateLimitResult checkRateLimitWithResult(String userId, String operation) {
        if (!rateLimitEnabled) {
            return RateLimitResult.unlimited();
        }

        // Admin users bypass rate limiting
        try {
            Long userIdLong = Long.parseLong(userId);
            Optional<User> userOpt = userService.getUserWithVersion(userIdLong);
            if (userOpt.isPresent() && userOpt.get().isAdmin()) {
                log.debug("Admin user {} bypassing rate limit for operation {}", userId, operation);
                return RateLimitResult.unlimited();
            }
        } catch (NumberFormatException e) {
            // userId is not a number (e.g., IP address), continue with rate limiting
            log.debug("Non-numeric userId {}, applying rate limits", userId);
        }

        String bucketKey = generateBucketKey(userId, operation);
        BucketConfiguration config = getConfigurationForOperation(operation);

        Bucket bucket =
                lettuceBasedProxyManager.builder().build(bucketKey.getBytes(), () -> config);

        if (bucket.tryConsume(1)) {
            long remainingTokens = bucket.getAvailableTokens();
            return RateLimitResult.allowed(remainingTokens);
        } else {
            long waitTimeMillis =
                    bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill() / 1_000_000;
            log.warn("Rate limit exceeded for user {} on operation {}", userId, operation);
            throw new RateLimitExceededException(
                    String.format(
                            "Rate limit exceeded for operation '%s'. Try again in %d seconds.",
                            operation, waitTimeMillis / 1000));
        }
    }

    /** Get current rate limit status without consuming tokens */
    public RateLimitStatus getRateLimitStatus(String userId, String operation) {
        if (!rateLimitEnabled) {
            return RateLimitStatus.unlimited();
        }

        String bucketKey = generateBucketKey(userId, operation);
        BucketConfiguration config = getConfigurationForOperation(operation);

        Bucket bucket =
                lettuceBasedProxyManager.builder().build(bucketKey.getBytes(), () -> config);

        long availableTokens = bucket.getAvailableTokens();
        long capacity = config.getBandwidths()[0].getCapacity();

        return RateLimitStatus.builder()
                .allowed(availableTokens > 0)
                .remainingTokens(availableTokens)
                .totalCapacity(capacity)
                .resetTimeMillis(calculateResetTime(bucket))
                .build();
    }

    /** Reset rate limit for a user (admin operation) */
    public void resetRateLimit(String userId, String operation) {
        String bucketKey = generateBucketKey(userId, operation);

        // Remove the bucket to reset it
        try {
            redisClient.connect().sync().del(bucketKey);
            log.info("Reset rate limit for user {} on operation {}", userId, operation);
        } catch (Exception e) {
            log.error(
                    "Failed to reset rate limit for user {} on operation {}: {}",
                    userId,
                    operation,
                    e.getMessage());
        }
    }

    // Private helper methods

    private String generateBucketKey(String userId, String operation) {
        return String.format("rate_limit:%s:%s", operation, userId);
    }

    private BucketConfiguration getConfigurationForOperation(String operation) {
        return configCache.computeIfAbsent(operation, this::createConfigurationForOperation);
    }

    private BucketConfiguration createConfigurationForOperation(String operation) {
        return switch (operation.toLowerCase()) {
            case "create_order" ->
                    BucketConfiguration.builder()
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(ordersPerMinute)
                                            .refillGreedy(ordersPerMinute, Duration.ofMinutes(1))
                                            .build())
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(ordersPerHour)
                                            .refillGreedy(ordersPerHour, Duration.ofHours(1))
                                            .build())
                            .build();

            case "api_call" ->
                    BucketConfiguration.builder()
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(apiCallsPerMinute)
                                            .refillGreedy(apiCallsPerMinute, Duration.ofMinutes(1))
                                            .build())
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(apiCallsPerHour)
                                            .refillGreedy(apiCallsPerHour, Duration.ofHours(1))
                                            .build())
                            .build();

            case "auth_attempt", "login" ->
                    BucketConfiguration.builder()
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(authAttemptsPerMinute)
                                            .refillGreedy(
                                                    authAttemptsPerMinute, Duration.ofMinutes(1))
                                            .build())
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(authAttemptsPerMinute * 5)
                                            .refillGreedy(
                                                    authAttemptsPerMinute * 5, Duration.ofHours(1))
                                            .build())
                            .build();

            case "password_reset" ->
                    BucketConfiguration.builder()
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(3)
                                            .refillGreedy(3, Duration.ofMinutes(15))
                                            .build())
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(5)
                                            .refillGreedy(5, Duration.ofHours(1))
                                            .build())
                            .build();

            case "video_processing" ->
                    BucketConfiguration.builder()
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(5)
                                            .refillGreedy(5, Duration.ofMinutes(1))
                                            .build())
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(20)
                                            .refillGreedy(20, Duration.ofHours(1))
                                            .build())
                            .build();

            default ->
                    BucketConfiguration.builder()
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(30)
                                            .refillGreedy(30, Duration.ofMinutes(1))
                                            .build())
                            .addLimit(
                                    Bandwidth.builder()
                                            .capacity(200)
                                            .refillGreedy(200, Duration.ofHours(1))
                                            .build())
                            .build();
        };
    }

    private long calculateResetTime(Bucket bucket) {
        try {
            return bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill() / 1_000_000;
        } catch (Exception e) {
            return 0;
        }
    }

    // Supporting classes

    public static class RateLimitResult {
        private final boolean allowed;
        private final long remainingTokens;
        private final boolean unlimited;

        private RateLimitResult(boolean allowed, long remainingTokens, boolean unlimited) {
            this.allowed = allowed;
            this.remainingTokens = remainingTokens;
            this.unlimited = unlimited;
        }

        public static RateLimitResult allowed(long remainingTokens) {
            return new RateLimitResult(true, remainingTokens, false);
        }

        public static RateLimitResult denied() {
            return new RateLimitResult(false, 0, false);
        }

        public static RateLimitResult unlimited() {
            return new RateLimitResult(true, Long.MAX_VALUE, true);
        }

        // Getters
        public boolean isAllowed() {
            return allowed;
        }

        public long getRemainingTokens() {
            return remainingTokens;
        }

        public boolean isUnlimited() {
            return unlimited;
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class RateLimitStatus {
        private final boolean allowed;
        private final long remainingTokens;
        private final long totalCapacity;
        private final long resetTimeMillis;

        public static RateLimitStatus unlimited() {
            return RateLimitStatus.builder()
                    .allowed(true)
                    .remainingTokens(Long.MAX_VALUE)
                    .totalCapacity(Long.MAX_VALUE)
                    .resetTimeMillis(0)
                    .build();
        }
    }
}
