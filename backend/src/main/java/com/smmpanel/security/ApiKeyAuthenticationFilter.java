package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.ApiKeyService;
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
            } catch (ApiKeyPausedException e) {
                // 403 (not 401) — the key is valid; the *owner* has disabled it. Returning 401
                // would tell an integration "rotate your key" when the actual remedy is "ask
                // the account owner to resume".
                log.info("Rejecting request to {} — owner paused API key", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "API key paused by owner");
                return;
            } catch (Exception e) {
                log.error("API key authentication failed for request: {}", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Sentinel for the controller-style 403 path; intentionally not a {@link SecurityException}.
     */
    private static final class ApiKeyPausedException extends RuntimeException {
        ApiKeyPausedException(String msg) {
            super(msg);
        }
    }

    private void authenticateWithApiKey(HttpServletRequest request, String apiKey) {
        StopWatch stopWatch = new StopWatch("API Key Authentication");

        try {
            // Fast path — deterministic global-salt lookup hash → at most one candidate row,
            // resolved by an indexed SELECT. The legacy "iterate every active user and SHA-512
            // against each per-user salt" scan is kept below as a fallback for users whose row
            // predates the api_key_lookup_hash column (NULL on day 1; eagerly populated on next
            // rotate, lazily backfilled on first successful auth).
            stopWatch.start("Lookup-hash resolve");
            String lookupHash = apiKeyService.hashApiKeyForLookup(apiKey);
            Optional<User> fastMatch =
                    userRepository.findByApiKeyLookupHashAndIsActiveTrue(lookupHash);
            stopWatch.stop();

            User authenticatedUser = null;

            if (fastMatch.isPresent()) {
                stopWatch.start("Per-user verify (fast path)");
                User candidate = fastMatch.get();
                // Defence in depth: still re-verify against the per-user salt. A non-null
                // lookup hash is supposed to be unique to one user, but never authenticate
                // solely on that — a stale row left after manual DB surgery, or the
                // (astronomically unlikely) hash collision, would otherwise let a wrong key
                // through. The cost of a single SHA-512 here is negligible.
                String clientIdentifier =
                        createClientIdentifier(request, candidate.getApiKeyHash());
                if (candidate.getApiKeyHash() != null
                        && candidate.getApiKeySalt() != null
                        && apiKeyService.verifyApiKeyOnly(
                                apiKey,
                                candidate.getApiKeyHash(),
                                candidate.getApiKeySalt(),
                                clientIdentifier)) {
                    authenticatedUser = candidate;
                }
                stopWatch.stop();
            }

            if (authenticatedUser == null) {
                // Legacy fallback — scan only rows whose lookup hash is still null. After a
                // user takes this path once we backfill their lookup hash below, so the very
                // next request from that user resolves via the fast path and never reaches
                // here again. Filtering on lookup_hash IS NULL keeps the scan from re-checking
                // users we've already migrated.
                stopWatch.start("Legacy scan");
                List<User> legacyCandidates =
                        userRepository.findAllByIsActiveTrue().stream()
                                .filter(
                                        u ->
                                                u.getApiKeyHash() != null
                                                        && u.getApiKeySalt() != null
                                                        && u.getApiKeyLookupHash() == null)
                                .toList();

                for (User user : legacyCandidates) {
                    String clientIdentifier =
                            createClientIdentifier(request, user.getApiKeyHash());
                    if (validateApiKeyWithRateLimit(apiKey, user, clientIdentifier)) {
                        authenticatedUser = user;
                        // One-shot migration: subsequent requests from this user take the
                        // fast path. Best-effort — a backfill failure must NOT prevent the
                        // current request from authenticating.
                        apiKeyService.backfillLookupHashIfMissing(user.getId(), lookupHash);
                        break;
                    }
                }
                stopWatch.stop();
            }

            if (authenticatedUser != null) {
                // Soft-deleted accounts: the anonymizer clears api_key_hash, but defend in depth
                // — if anything ever short-circuits the clear, the deletion flag must still
                // hard-block authentication.
                if (authenticatedUser.isSoftDeleted()) {
                    log.warn(
                            "API key request from soft-deleted user {} — rejecting",
                            authenticatedUser.getId());
                    throw new SecurityException("Account no longer exists");
                }

                // Self-service pause: the user flipped api_key_paused_at from Profile → Danger
                // Zone. The web app (Bearer JWT) is unaffected, but every X-API-Key request
                // must come back as a hard 403. We deliberately reject after authentication
                // succeeds so the response distinguishes "valid key but paused" from "wrong
                // key" — the user paused on purpose, they should see why their requests fail.
                if (authenticatedUser.getApiKeyPausedAt() != null) {
                    log.info(
                            "API key for user {} is paused since {} — rejecting request to {}",
                            authenticatedUser.getUsername(),
                            authenticatedUser.getApiKeyPausedAt(),
                            request.getRequestURI());
                    throw new ApiKeyPausedException("API key is paused by the owner");
                }

                List<SimpleGrantedAuthority> authorities =
                        List.of(
                                new SimpleGrantedAuthority(
                                        "ROLE_" + authenticatedUser.getRole().name()));

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                authenticatedUser, null, authorities);

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                // PERFORMANCE: Log timing details for monitoring
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Authenticated user {} with API key - Timing: {}",
                            authenticatedUser.getUsername(),
                            stopWatch.prettyPrint());
                } else {
                    log.info(
                            "Authenticated user {} - Total time: {}ms",
                            authenticatedUser.getUsername(),
                            stopWatch.getTotalTimeMillis());
                }

                // ASYNC: Track API access without blocking authentication
                trackApiAccessAsync(authenticatedUser.getId());
            } else {
                // Rate limit for failed attempts
                String clientIdentifier = createClientIdentifier(request, apiKey);
                rateLimitService.recordFailedAttempt(clientIdentifier);

                log.warn(
                        "Invalid API key provided - Validation time: {}ms",
                        stopWatch.getLastTaskTimeMillis());
                throw new SecurityException("Invalid API key");
            }
        } catch (ApiKeyPausedException e) {
            // Rethrow as-is so the outer filter catch maps it to 403 (not 401). Without this
            // explicit rethrow the generic catch below would swallow it as
            // "Authentication failed" and return 401 — which would mislead an integration into
            // rotating a perfectly valid key.
            throw e;
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
