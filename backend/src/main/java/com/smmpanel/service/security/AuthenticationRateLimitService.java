package com.smmpanel.service.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Rate limiting service for authentication attempts to prevent brute force attacks
 * Uses Redis for distributed rate limiting across multiple application instances
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationRateLimitService {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.security.rate-limit.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.security.rate-limit.window-minutes:15}")
    private int windowMinutes;

    @Value("${app.security.rate-limit.lockout-minutes:30}")
    private int lockoutMinutes;

    private static final String RATE_LIMIT_PREFIX = "auth:rate_limit:";
    private static final String LOCKOUT_PREFIX = "auth:lockout:";

    /**
     * Check if authentication attempts are rate limited for the given identifier
     * @param identifier The identifier to check (IP address, API key hash prefix, etc.)
     * @return true if rate limited, false if attempts are allowed
     */
    public boolean isRateLimited(String identifier) {
        try {
            String lockoutKey = LOCKOUT_PREFIX + identifier;
            
            // Check if currently locked out
            if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
                Long ttl = redisTemplate.getExpire(lockoutKey, TimeUnit.SECONDS);
                log.warn("Authentication attempts blocked for identifier: {} (lockout expires in {}s)", 
                    maskIdentifier(identifier), ttl);
                return true;
            }

            String rateLimitKey = RATE_LIMIT_PREFIX + identifier;
            String attempts = redisTemplate.opsForValue().get(rateLimitKey);
            
            if (attempts != null) {
                int attemptCount = Integer.parseInt(attempts);
                if (attemptCount >= maxAttempts) {
                    // Lock out the identifier
                    redisTemplate.opsForValue().set(lockoutKey, "locked", 
                        Duration.ofMinutes(lockoutMinutes));
                    redisTemplate.delete(rateLimitKey); // Clear attempt counter
                    
                    log.warn("Maximum authentication attempts exceeded for identifier: {} - Locked out for {} minutes", 
                        maskIdentifier(identifier), lockoutMinutes);
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            log.error("Error checking rate limit for identifier: {}", maskIdentifier(identifier), e);
            // Fail open - don't block legitimate users due to Redis issues
            return false;
        }
    }

    /**
     * Record a failed authentication attempt
     * @param identifier The identifier for the failed attempt
     */
    public void recordFailedAttempt(String identifier) {
        try {
            String rateLimitKey = RATE_LIMIT_PREFIX + identifier;
            
            // Increment attempt counter with expiry
            Long attempts = redisTemplate.opsForValue().increment(rateLimitKey);
            if (attempts == 1) {
                // Set expiry only on first attempt
                redisTemplate.expire(rateLimitKey, Duration.ofMinutes(windowMinutes));
            }
            
            log.info("Failed authentication attempt #{} recorded for identifier: {} (window: {}min)", 
                attempts, maskIdentifier(identifier), windowMinutes);
                
        } catch (Exception e) {
            log.error("Error recording failed attempt for identifier: {}", maskIdentifier(identifier), e);
        }
    }

    /**
     * Record a successful authentication (clears failed attempts)
     * @param identifier The identifier for the successful attempt
     */
    public void recordSuccessfulAttempt(String identifier) {
        try {
            String rateLimitKey = RATE_LIMIT_PREFIX + identifier;
            String lockoutKey = LOCKOUT_PREFIX + identifier;
            
            // Clear both failed attempts and any lockout
            redisTemplate.delete(rateLimitKey);
            redisTemplate.delete(lockoutKey);
            
            log.debug("Successful authentication - cleared rate limit data for identifier: {}", 
                maskIdentifier(identifier));
                
        } catch (Exception e) {
            log.error("Error clearing rate limit data for identifier: {}", maskIdentifier(identifier), e);
        }
    }

    /**
     * Get current failed attempt count for identifier
     * @param identifier The identifier to check
     * @return Current failed attempt count
     */
    public int getFailedAttemptCount(String identifier) {
        try {
            String rateLimitKey = RATE_LIMIT_PREFIX + identifier;
            String attempts = redisTemplate.opsForValue().get(rateLimitKey);
            return attempts != null ? Integer.parseInt(attempts) : 0;
        } catch (Exception e) {
            log.error("Error getting failed attempt count for identifier: {}", maskIdentifier(identifier), e);
            return 0;
        }
    }

    /**
     * Get remaining lockout time in seconds
     * @param identifier The identifier to check
     * @return Remaining lockout time in seconds, 0 if not locked out
     */
    public long getLockoutTimeRemaining(String identifier) {
        try {
            String lockoutKey = LOCKOUT_PREFIX + identifier;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey))) {
                return redisTemplate.getExpire(lockoutKey, TimeUnit.SECONDS);
            }
            return 0;
        } catch (Exception e) {
            log.error("Error getting lockout time for identifier: {}", maskIdentifier(identifier), e);
            return 0;
        }
    }

    /**
     * Manually clear rate limit data for an identifier (admin function)
     * @param identifier The identifier to clear
     */
    public void clearRateLimit(String identifier) {
        try {
            String rateLimitKey = RATE_LIMIT_PREFIX + identifier;
            String lockoutKey = LOCKOUT_PREFIX + identifier;
            
            redisTemplate.delete(rateLimitKey);
            redisTemplate.delete(lockoutKey);
            
            log.info("Manually cleared rate limit data for identifier: {}", maskIdentifier(identifier));
        } catch (Exception e) {
            log.error("Error clearing rate limit data for identifier: {}", maskIdentifier(identifier), e);
        }
    }

    /**
     * Create a safe identifier from IP address for rate limiting
     * @param ipAddress The IP address
     * @return Safe identifier for rate limiting
     */
    public String createIpIdentifier(String ipAddress) {
        if (ipAddress == null) {
            return "unknown";
        }
        // For IPv4, use full address. For IPv6, use first 64 bits to avoid tracking individual devices
        if (ipAddress.contains(":")) {
            // IPv6 - use network prefix
            String[] parts = ipAddress.split(":");
            if (parts.length >= 4) {
                return String.join(":", parts[0], parts[1], parts[2], parts[3]) + "::";
            }
        }
        return ipAddress;
    }

    /**
     * Create a safe identifier from API key hash for rate limiting
     * @param apiKeyHash The API key hash
     * @return Safe identifier for rate limiting (first 8 characters)
     */
    public String createApiKeyIdentifier(String apiKeyHash) {
        if (apiKeyHash == null || apiKeyHash.length() < 8) {
            return "invalid";
        }
        // Use first 8 characters of hash for rate limiting
        return "api:" + apiKeyHash.substring(0, 8);
    }

    /**
     * Mask identifier for safe logging
     */
    private String maskIdentifier(String identifier) {
        if (identifier == null || identifier.length() <= 4) {
            return "[masked]";
        }
        if (identifier.length() <= 8) {
            return identifier.substring(0, 2) + "***";
        }
        return identifier.substring(0, 4) + "***" + identifier.substring(identifier.length() - 2);
    }
}