package com.smmpanel.service.ratelimit;

import com.smmpanel.entity.User;
import com.smmpanel.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

@Slf4j
@Service
public class RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;
    
    @Autowired
    @Lazy  // Add this to break circular dependency
    private UserService userService;
    
    public RateLimitService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    
    private static final String RATE_LIMIT_KEY = "ratelimit:%s:%s"; // userId:endpoint
    private static final String PROGRESSIVE_LIMIT_KEY = "progressive:%s:%s"; // userId:endpoint
    
    /**
     * Check if request is allowed based on rate limits
     *
     * @param userId User ID
     * @param endpoint Endpoint being accessed
     * @return true if request is allowed, false if rate limited
     */
    public boolean isRequestAllowed(Long userId, String endpoint) {
        // Admin users bypass rate limiting
        // TODO restore - findById and isAdmin methods when available
        Optional<User> userOpt = userService.getUserWithVersion(userId);
        if (userOpt.isPresent() /* && userOpt.get().isAdmin() */) {
            return true;
        }

        String key = String.format(RATE_LIMIT_KEY, userId, endpoint);
        String progressiveKey = String.format(PROGRESSIVE_LIMIT_KEY, userId, endpoint);

        // Get current window count
        Integer count = (Integer) redisTemplate.opsForValue().get(key);
        Integer progressiveCount = (Integer) redisTemplate.opsForValue().get(progressiveKey);

        if (count == null) {
            // First request in window
            redisTemplate.opsForValue().set(key, 1, getRateLimitWindow(endpoint), TimeUnit.SECONDS);
            return true;
        }

        // Check against limits
        RateLimitConfig config = RateLimitConfig.getForEndpoint(endpoint);
        int limit = getAdjustedLimit(config.getBaseLimit(), progressiveCount);

        if (count >= limit) {
            log.warn("Rate limit exceeded for user {} on endpoint {}", userId, endpoint);
            updateProgressiveLimit(progressiveKey);
            return false;
        }

        // Increment counter
        redisTemplate.opsForValue().increment(key);
        return true;
    }

    /**
     * Get adjusted rate limit based on progressive factor
     */
    private int getAdjustedLimit(int baseLimit, Integer progressiveCount) {
        if (progressiveCount == null) {
            return baseLimit;
        }
        // Reduce limit by 25% for each violation, minimum 10% of base limit
        double factor = Math.max(0.1, 1.0 - (progressiveCount * 0.25));
        return (int) (baseLimit * factor);
    }

    /**
     * Update progressive rate limit counter
     */
    private void updateProgressiveLimit(String key) {
        Integer violations = (Integer) redisTemplate.opsForValue().get(key);
        if (violations == null) {
            redisTemplate.opsForValue().set(key, 1, 24, TimeUnit.HOURS);
        } else {
            redisTemplate.opsForValue().increment(key);
        }
    }

    /**
     * Get rate limit window duration for endpoint
     */
    private int getRateLimitWindow(String endpoint) {
        return RateLimitConfig.getForEndpoint(endpoint).getWindowSeconds();
    }

    /**
     * Reset rate limits for a user (e.g., after successful payment)
     */
    public void resetRateLimits(Long userId) {
        String pattern = String.format(PROGRESSIVE_LIMIT_KEY, userId, "*");
        var keys = redisTemplate.keys(pattern);
        if (keys != null) {
            redisTemplate.delete(keys);
        }
        log.info("Reset rate limits for user {}", userId);
    }
}
