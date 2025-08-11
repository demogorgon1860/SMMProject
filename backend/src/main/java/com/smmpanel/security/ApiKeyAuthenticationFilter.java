package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.ApiKeyService;
import com.smmpanel.service.security.AuthenticationRateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PRODUCTION-READY API Key Authentication Filter
 *
 * <p>SECURITY IMPROVEMENTS: 1. Uses proper salted hash from ApiKeyService 2. Fixed O(n) lookup
 * performance issue - now uses indexed query 3. Removed database writes from authentication path 4.
 * Added proper error handling 5. Implemented secure API key validation 6. Added rate limiting for
 * brute force protection
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "key"; // Perfect Panel compatibility

    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;
    private final AuthenticationRateLimitService rateLimitService;

    @Value("${app.security.api-key.enabled:true}")
    private boolean apiKeyAuthEnabled;

    @Value("${app.security.api-key.header:X-API-Key}")
    private String apiKeyHeader;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!apiKeyAuthEnabled || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = extractApiKey(request);

        if (StringUtils.isNotBlank(apiKey)) {
            try {
                authenticateWithApiKey(request, apiKey);
            } catch (Exception e) {
                log.error("API key authentication failed: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticateWithApiKey(HttpServletRequest request, String apiKey) {
        StopWatch stopWatch = new StopWatch("API Key Authentication");

        try {
            stopWatch.start("Hash API Key");
            String hashedApiKey = apiKeyService.hashApiKeyForLookup(apiKey);
            stopWatch.stop();

            stopWatch.start("Database Lookup");
            // OPTIMIZED: Uses partial index idx_users_api_key_hash_active for performance
            Optional<User> userOpt = userRepository.findByApiKeyHashAndIsActiveTrue(hashedApiKey);
            stopWatch.stop();

            if (userOpt.isPresent()) {
                User user = userOpt.get();

                stopWatch.start("API Key Validation");
                // Create rate limiting identifier from IP and API key
                String clientIdentifier = createClientIdentifier(request, hashedApiKey);

                // SECURITY: Use enhanced validation with rate limiting
                if (validateApiKeyWithRateLimit(apiKey, user, clientIdentifier)) {
                    stopWatch.stop();

                    List<SimpleGrantedAuthority> authorities =
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(user, null, authorities);

                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    // PERFORMANCE: Log timing details for monitoring
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Authenticated user {} with API key - Timing: {}",
                                user.getUsername(),
                                stopWatch.prettyPrint());
                    } else {
                        log.info(
                                "Authenticated user {} - Total time: {}ms",
                                user.getUsername(),
                                stopWatch.getTotalTimeMillis());
                    }

                    // ASYNC: Track API access without blocking authentication
                    trackApiAccessAsync(user.getId());
                } else {
                    stopWatch.stop();
                    log.warn(
                            "API key verification failed for user {} - Timing: {}",
                            user.getUsername(),
                            stopWatch.prettyPrint());
                    throw new SecurityException("API key verification failed");
                }
            } else {
                // Rate limit even for non-existent API keys
                String clientIdentifier = createClientIdentifier(request, hashedApiKey);
                rateLimitService.recordFailedAttempt(clientIdentifier);

                log.warn(
                        "Invalid API key provided - Hash lookup time: {}ms",
                        stopWatch.getLastTaskTimeMillis());
                throw new SecurityException("Invalid API key");
            }
        } catch (DataAccessException e) {
            log.error(
                    "Database connection error during API key authentication: {}", e.getMessage());
            throw new SecurityException("Authentication service temporarily unavailable", e);
        } catch (Exception e) {
            log.error("Unexpected error during API key authentication: {}", e.getMessage(), e);
            throw new SecurityException("Authentication failed", e);
        }
    }

    /** Validate API key with enhanced security and rate limiting */
    private boolean validateApiKeyWithRateLimit(String apiKey, User user, String clientIdentifier) {
        if (user == null || user.getApiKeyHash() == null || user.getApiKeySalt() == null) {
            return false;
        }

        try {
            return apiKeyService.verifyApiKeyOnly(
                    apiKey, user.getApiKeyHash(), user.getApiKeySalt(), clientIdentifier);
        } catch (Exception e) {
            log.error("Error validating API key for user: {}", user.getUsername(), e);
            return false;
        }
    }

    /** Create client identifier for rate limiting (combines IP and API key prefix) */
    private String createClientIdentifier(HttpServletRequest request, String hashedApiKey) {
        String clientIp = getClientIpAddress(request);
        String ipIdentifier = rateLimitService.createIpIdentifier(clientIp);
        String apiKeyIdentifier = rateLimitService.createApiKeyIdentifier(hashedApiKey);
        return ipIdentifier + ":" + apiKeyIdentifier;
    }

    /** Get client IP address with proxy support */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(xRealIp)) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    /** Track API access asynchronously to avoid blocking authentication */
    private void trackApiAccessAsync(Long userId) {
        // TODO: Implement async tracking using @Async or message queue
        // This could update last_api_access_at field in background
        log.debug("API access tracking queued for user ID: {}", userId);
    }

    private String extractApiKey(HttpServletRequest request) {
        // Check header first (more secure)
        String apiKey = request.getHeader(apiKeyHeader);

        // Fall back to parameter for Perfect Panel compatibility
        if (StringUtils.isBlank(apiKey)) {
            apiKey = request.getParameter(API_KEY_PARAM);
        }

        return StringUtils.trimToNull(apiKey);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();

        // Skip API key auth for public endpoints
        return path.startsWith("/api/v2/auth/")
                || path.startsWith("/api/v2/public/")
                || path.startsWith("/actuator/health")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs");
    }
}
