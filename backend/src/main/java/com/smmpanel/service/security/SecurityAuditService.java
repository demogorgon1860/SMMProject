package com.smmpanel.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityAuditService {

    private final ObjectMapper objectMapper;

    /** Log authentication attempt */
    public void logAuthenticationAttempt(
            String username, String ipAddress, boolean success, String details) {
        Map<String, Object> auditData =
                Map.of(
                        "event", "AUTHENTICATION_ATTEMPT",
                        "timestamp", LocalDateTime.now(),
                        "username", username,
                        "ipAddress", ipAddress,
                        "success", success,
                        "details", details);
        logSecurityEvent(auditData);
    }

    /** Log account lockout */
    public void logAccountLockout(String username, String ipAddress, int failedAttempts) {
        Map<String, Object> auditData =
                Map.of(
                        "event", "ACCOUNT_LOCKOUT",
                        "timestamp", LocalDateTime.now(),
                        "username", username,
                        "ipAddress", ipAddress,
                        "failedAttempts", failedAttempts);
        logSecurityEvent(auditData);
    }

    /** Log account unlock */
    public void logAccountUnlock(String username, String adminUser, String reason) {
        Map<String, Object> auditData =
                Map.of(
                        "event", "ACCOUNT_UNLOCK",
                        "timestamp", LocalDateTime.now(),
                        "username", username,
                        "adminUser", adminUser,
                        "reason", reason);
        logSecurityEvent(auditData);
    }

    /** Log session invalidation */
    public void logSessionInvalidation(String username, String sessionId, String reason) {
        Map<String, Object> auditData =
                Map.of(
                        "event", "SESSION_INVALIDATION",
                        "timestamp", LocalDateTime.now(),
                        "username", username,
                        "sessionId", sessionId,
                        "reason", reason);
        logSecurityEvent(auditData);
    }

    /** Log suspicious activity */
    public void logSuspiciousActivity(
            String username, String ipAddress, String activityType, String details) {
        Map<String, Object> auditData =
                Map.of(
                        "event", "SUSPICIOUS_ACTIVITY",
                        "timestamp", LocalDateTime.now(),
                        "username", username,
                        "ipAddress", ipAddress,
                        "activityType", activityType,
                        "details", details);
        logSecurityEvent(auditData);
    }

    /** Log security event */
    private void logSecurityEvent(Map<String, Object> auditData) {
        try {
            String logMessage = objectMapper.writeValueAsString(auditData);
            log.info("SECURITY_AUDIT: {}", logMessage);
        } catch (Exception e) {
            log.error("Failed to log security event", e);
        }
    }
}
