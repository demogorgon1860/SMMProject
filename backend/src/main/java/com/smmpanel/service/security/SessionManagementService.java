package com.smmpanel.service.security;

import com.smmpanel.service.monitoring.SecurityMetricsService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManagementService {

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final int SESSION_TIMEOUT_MINUTES = 30;

    private final RedisTemplate<String, String> redisTemplate;
    private final SessionRegistry sessionRegistry;
    private final SecurityMetricsService securityMetricsService;
    private final SecurityAuditService securityAuditService;

    /** Create new session */
    public void createSession(String username, String sessionId) {
        String key = getSessionKey(username, sessionId);
        redisTemplate.opsForValue().set(key, "active");
        redisTemplate.expire(key, SESSION_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        securityAuditService.logSessionInvalidation(username, sessionId, "Session created");
    }

    /** Validate session */
    public boolean isSessionValid(String username, String sessionId) {
        String key = getSessionKey(username, sessionId);
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /** Extend session timeout */
    public void extendSession(String username, String sessionId) {
        String key = getSessionKey(username, sessionId);
        redisTemplate.expire(key, SESSION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    /** Invalidate single session */
    public void invalidateSession(String username, String sessionId, String reason) {
        String key = getSessionKey(username, sessionId);
        redisTemplate.delete(key);

        securityMetricsService.recordSessionInvalidation(username, reason);
        securityAuditService.logSessionInvalidation(username, sessionId, reason);
    }

    /** Invalidate all sessions for user */
    public void invalidateUserSessions(String username, String reason) {
        List<SessionInformation> sessions = sessionRegistry.getAllSessions(username, false);

        for (SessionInformation session : sessions) {
            invalidateSession(username, session.getSessionId(), reason);
            session.expireNow();
        }
    }

    /** Get all active sessions for user */
    public List<SessionInformation> getUserSessions(String username) {
        return sessionRegistry.getAllSessions(username, false);
    }

    /** Check concurrent session limit */
    public boolean isWithinSessionLimit(String username, int maxSessions) {
        List<SessionInformation> sessions = getUserSessions(username);
        return sessions.size() < maxSessions;
    }

    private String getSessionKey(String username, String sessionId) {
        return SESSION_KEY_PREFIX + username + ":" + sessionId;
    }
}
