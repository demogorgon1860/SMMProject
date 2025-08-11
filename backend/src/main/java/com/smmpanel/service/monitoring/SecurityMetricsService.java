package com.smmpanel.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityMetricsService {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> loginFailureCounters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> ipFailureCounters = new ConcurrentHashMap<>();

    /** Record failed login attempt */
    public void recordFailedLogin(String username, String ipAddress) {
        // Per-user metrics
        Counter userCounter =
                loginFailureCounters.computeIfAbsent(
                        username,
                        k ->
                                Counter.builder("security.login.failures")
                                        .tag("username", username)
                                        .description("Number of failed login attempts")
                                        .register(meterRegistry));
        userCounter.increment();

        // Per-IP metrics
        Counter ipCounter =
                ipFailureCounters.computeIfAbsent(
                        ipAddress,
                        k ->
                                Counter.builder("security.login.failures.ip")
                                        .tag("ip", ipAddress)
                                        .description("Number of failed login attempts per IP")
                                        .register(meterRegistry));
        ipCounter.increment();

        // Global metrics
        meterRegistry.counter("security.login.total.failures").increment();
    }

    /** Record successful login */
    public void recordSuccessfulLogin(String username, String ipAddress) {
        meterRegistry
                .counter("security.login.successes", "username", username, "ip", ipAddress)
                .increment();
    }

    /** Record account lockout */
    public void recordAccountLockout(String username) {
        meterRegistry.counter("security.account.lockouts", "username", username).increment();
    }

    /** Record CAPTCHA requirement */
    public void recordCaptchaRequired(String ipAddress) {
        meterRegistry.counter("security.captcha.required", "ip", ipAddress).increment();
    }

    /** Record account unlock */
    public void recordAccountUnlock(String username, String adminUser) {
        meterRegistry
                .counter("security.account.unlocks", "username", username, "admin", adminUser)
                .increment();
    }

    /** Record session invalidation */
    public void recordSessionInvalidation(String username, String reason) {
        meterRegistry
                .counter("security.session.invalidations", "username", username, "reason", reason)
                .increment();
    }

    /** Get failure count for user */
    public long getFailureCount(String username) {
        Counter counter = loginFailureCounters.get(username);
        return counter != null ? (long) counter.count() : 0;
    }

    /** Get failure count for IP */
    public long getIpFailureCount(String ipAddress) {
        Counter counter = ipFailureCounters.get(ipAddress);
        return counter != null ? (long) counter.count() : 0;
    }
}
