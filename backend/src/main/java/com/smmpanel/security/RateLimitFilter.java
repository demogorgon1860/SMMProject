package com.smmpanel.security;

import com.smmpanel.service.core.RateLimitService;
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
                            "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests."
                                    + " Please wait before trying again.\"}");
        }
    }

    private String getUserId(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }

        // Anonymous request — bucket per real client IP. We're behind Cloudflare + nginx in
        // prod, so request.getRemoteAddr() returns the proxy's IP (every anonymous user
        // shares the same bucket and gets rate-limited as one). CF-Connecting-IP and
        // X-Forwarded-For carry the real origin.
        return resolveClientIp(request);
    }

    /**
     * Extract the real client IP, preferring Cloudflare's verified header, then the first hop of
     * X-Forwarded-For, then X-Real-IP, finally falling back to remoteAddr. IPv4 is normalized to
     * canonical decimal form (so {@code 192.168.001.001} buckets with {@code 192.168.1.1}); IPv6
     * and anything else is lowercased and used as-is.
     *
     * <p>Important: do NOT pass arbitrary header content to {@link java.net.InetAddress#getByName}
     * — for hostname-shaped input it triggers a blocking DNS lookup, which is a remote DoS vector
     * since {@code X-Forwarded-For} is attacker-influenced. We parse IPv4 numerically instead.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String[] candidates = {
            request.getHeader("CF-Connecting-IP"),
            firstForwarded(request.getHeader("X-Forwarded-For")),
            request.getHeader("X-Real-IP"),
            request.getRemoteAddr()
        };
        for (String raw : candidates) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            return canonicalizeIp(trimmed);
        }
        return "unknown";
    }

    private static String firstForwarded(String header) {
        if (header == null) return null;
        int comma = header.indexOf(',');
        return comma < 0 ? header : header.substring(0, comma);
    }

    /**
     * Pure-string IP normalizer — never does DNS. IPv4 dotted-decimal is reduced to canonical form
     * (strips leading zeros, collapses {@code 192.168.001.001 → 192.168.1.1}); anything else (IPv6,
     * malformed, hostnames sneaked in via spoofed headers) is lowercased and used verbatim.
     * Worst-case for non-IPv4 input is two slightly-different representations of the same IPv6
     * hashing to separate buckets — that just means doubled quota for that one attacker, which is
     * far better than blocking the request thread on DNS.
     */
    private static String canonicalizeIp(String raw) {
        String ipv4 = canonicalizeIpv4(raw);
        if (ipv4 != null) return ipv4;
        return raw.toLowerCase();
    }

    private static String canonicalizeIpv4(String raw) {
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 4) return null;
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            for (int j = 0; j < p.length(); j++) {
                if (!Character.isDigit(p.charAt(j))) return null;
            }
            int n;
            try {
                n = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 0 || n > 255) return null;
            if (i > 0) sb.append('.');
            sb.append(n);
        }
        return sb.toString();
    }

    /**
     * Map the request to a rate-limit bucket name. Important: the panel runs API routes under
     * {@code /api/v1/...} (panel UI) and {@code /api/v2/...} (Perfect-Panel-compatible + admin), so
     * the matchers MUST cover both — an earlier version only matched v2 and silently routed every
     * v1 hit into the "default" bucket, which is looser than the intended auth/order limits.
     */
    private String determineOperation(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Normalize: strip ALL trailing slashes (Spring's default trailing-slash matching means
        // /register and /register/ hit the same controller, but a literal `path.equals(...)`
        // here would only match one — leaving the slashed variant to fall into the looser
        // auth_attempt bucket. Same defense for any double-slash a misbehaving proxy might
        // produce upstream.
        String normalizedPath = path;
        while (normalizedPath.length() > 1 && normalizedPath.endsWith("/")) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        // Registration is far more abusable than login (login fails harmlessly; register creates
        // accounts that bypass the email-verified gate for free welcome credit and for spam-vector
        // accounts). Tighter, dedicated bucket — must come BEFORE the broader auth_attempt match.
        if ("POST".equals(method)
                && (normalizedPath.equals("/api/v1/auth/register")
                        || normalizedPath.equals("/api/v2/auth/register")
                        || normalizedPath.equals("/api/auth/register"))) {
            return "register";
        }
        if (normalizedPath.startsWith("/api/v1/auth/")
                || normalizedPath.startsWith("/api/v2/auth/")
                || normalizedPath.startsWith("/api/auth/")) {
            return "auth_attempt";
        } else if ((normalizedPath.startsWith("/api/v1/orders")
                        || normalizedPath.startsWith("/api/v2/orders"))
                && "POST".equals(method)) {
            return "create_order";
        } else if (normalizedPath.startsWith("/api/v1/") || normalizedPath.startsWith("/api/v2/")) {
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
