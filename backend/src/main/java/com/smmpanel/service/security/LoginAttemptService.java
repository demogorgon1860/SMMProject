package com.smmpanel.service.security;

import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.monitoring.SecurityMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final SecurityMetricsService securityMetrics;
    private final UserRepository userRepository;

    private static final String ATTEMPTS_KEY = "login_attempts:%s";
    private static final String LOCKOUT_KEY = "account_lockout:%s";
    private static final String CAPTCHA_KEY = "require_captcha:%s";

    @Value("${security.login.maxAttempts:5}")
    private int maxAttempts;

    @Value("${security.login.lockoutDuration:300}") // 5 minutes
    private int lockoutDuration;

    @Value("${security.login.captchaThreshold:3}")
    private int captchaThreshold;

    /**
     * Record failed login attempt
     */
    public void loginFailed(String username, String ipAddress) {
        String attemptsKey = String.format(ATTEMPTS_KEY, username);
        String lockoutKey = String.format(LOCKOUT_KEY, username);
        String captchaKey = String.format(CAPTCHA_KEY, ipAddress);

        // Increment failed attempts
        Integer attempts = (Integer) redisTemplate.opsForValue().get(attemptsKey);
        int newAttempts = attempts == null ? 1 : attempts + 1;
        
        redisTemplate.opsForValue().set(attemptsKey, newAttempts, 1, TimeUnit.HOURS);

        // Check for lockout threshold
        if (newAttempts >= maxAttempts) {
            lockAccount(username);
            log.warn("Account locked due to multiple failed attempts: {}", username);
            securityMetrics.recordAccountLockout(username);
        }

        // Enable CAPTCHA after threshold
        if (newAttempts >= captchaThreshold) {
            redisTemplate.opsForValue().set(captchaKey, true, 1, TimeUnit.HOURS);
            securityMetrics.recordCaptchaRequired(ipAddress);
        }

        securityMetrics.recordFailedLogin(username, ipAddress);
        log.info("Failed login attempt {} for user: {}, IP: {}", newAttempts, username, ipAddress);
    }

    /**
     * Record successful login
     */
    public void loginSucceeded(String username, String ipAddress) {
        String attemptsKey = String.format(ATTEMPTS_KEY, username);
        String captchaKey = String.format(CAPTCHA_KEY, ipAddress);

        // Clear failed attempts
        redisTemplate.delete(attemptsKey);
        redisTemplate.delete(captchaKey);

        securityMetrics.recordSuccessfulLogin(username, ipAddress);
        log.info("Successful login for user: {}, IP: {}", username, ipAddress);
    }

    /**
     * Check if account is locked
     */
    public boolean isAccountLocked(String username) {
        String lockoutKey = String.format(LOCKOUT_KEY, username);
        return Boolean.TRUE.equals(redisTemplate.hasKey(lockoutKey));
    }

    /**
     * Check if CAPTCHA is required
     */
    public boolean isCaptchaRequired(String ipAddress) {
        String captchaKey = String.format(CAPTCHA_KEY, ipAddress);
        return Boolean.TRUE.equals(redisTemplate.hasKey(captchaKey));
    }

    /**
     * Lock account
     */
    private void lockAccount(String username) {
        String lockoutKey = String.format(LOCKOUT_KEY, username);
        redisTemplate.opsForValue().set(lockoutKey, true, lockoutDuration, TimeUnit.SECONDS);

        // Update user status in database
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setLocked(true);
            user.setLockedAt(Instant.now());
            userRepository.save(user);
        });
    }

    /**
     * Unlock account manually (for admin use)
     */
    public void unlockAccount(String username, String adminUser) {
        String lockoutKey = String.format(LOCKOUT_KEY, username);
        redisTemplate.delete(lockoutKey);

        // Update user status in database
        userRepository.findByUsername(username).ifPresent(user -> {
            user.setLocked(false);
            user.setLockedAt(null);
            userRepository.save(user);
        });

        log.info("Account unlocked by admin: {} for user: {}", adminUser, username);
        securityMetrics.recordAccountUnlock(username, adminUser);
    }

    /**
     * Get remaining lockout time in seconds
     */
    public long getRemainingLockoutTime(String username) {
        String lockoutKey = String.format(LOCKOUT_KEY, username);
        Long expiry = redisTemplate.getExpire(lockoutKey, TimeUnit.SECONDS);
        return expiry != null ? expiry : 0;
    }

    /**
     * Get current attempt count
     */
    public int getAttemptCount(String username) {
        String attemptsKey = String.format(ATTEMPTS_KEY, username);
        Integer attempts = (Integer) redisTemplate.opsForValue().get(attemptsKey);
        return attempts != null ? attempts : 0;
    }
}
