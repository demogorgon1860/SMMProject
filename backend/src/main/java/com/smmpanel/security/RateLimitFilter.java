package com.smmpanel.security;

import com.smmpanel.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * PRODUCTION-READY Rate Limiting Filter
 *
 * <p>IMPROVEMENTS: 1. Uses RateLimitService for proper rate limiting 2. User-specific rate limiting
 * 3. Different limits for different operations 4. Proper error handling and logging
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;

    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!rateLimitEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String userId = getUserId(request);
            String operation = determineOperation(request);

            // Check rate limit
            rateLimitService.checkRateLimit(userId, operation);

            // Add rate limit headers
            addRateLimitHeaders(response, userId, operation);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.warn("Rate limit exceeded: {}", e.getMessage());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter()
                    .write(
                            "{\"error\":\"Rate limit exceeded\",\"message\":\""
                                    + e.getMessage()
                                    + "\"}");
        }
    }

    private String getUserId(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }

        // For API key requests, use IP address as fallback
        return request.getRemoteAddr();
    }

    private String determineOperation(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (path.startsWith("/api/v2/auth/")) {
            return "auth_attempt";
        } else if (path.startsWith("/api/v2/orders") && "POST".equals(method)) {
            return "create_order";
        } else if (path.startsWith("/api/v2/")) {
            return "api_call";
        } else {
            return "default";
        }
    }

    private void addRateLimitHeaders(
            HttpServletResponse response, String userId, String operation) {
        try {
            RateLimitService.RateLimitStatus status =
                    rateLimitService.getRateLimitStatus(userId, operation);

            response.setHeader("X-RateLimit-Limit", String.valueOf(status.getTotalCapacity()));
            response.setHeader(
                    "X-RateLimit-Remaining", String.valueOf(status.getRemainingTokens()));
            response.setHeader("X-RateLimit-Reset", String.valueOf(status.getResetTimeMillis()));

        } catch (Exception e) {
            log.debug("Could not add rate limit headers: {}", e.getMessage());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();

        // Skip rate limiting for health checks and static resources
        return path.startsWith("/actuator/health")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/error");
    }
}
