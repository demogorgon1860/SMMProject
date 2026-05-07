package com.smmpanel.security;

import com.smmpanel.config.JwtConfig;
import io.jsonwebtoken.*;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtConfig jwtConfig;
    private final Key jwtSecretKey;

    @org.springframework.beans.factory.annotation.Value(
            "${app.jwt.refresh-expiration:604800000}") // 7 days default
    private long refreshExpiration;

    // -----------------------------------------------------------------------
    // In-memory parsed-claims cache.
    //
    // Hot path: JwtAuthenticationFilter parses the same Bearer token on every
    // authenticated request, and {@link #isTokenValid(String, UserDetails)}
    // internally re-extracts the username AND the expiration — three full
    // HMAC-SHA-256 verifies per request, each ~1–3 ms. For an active dashboard
    // (~5–10 RPS per operator) that's a measurable fraction of the response
    // budget on otherwise-cheap endpoints like {@code /balance} or
    // {@code /auth/me}.
    //
    // The cache is keyed by the (immutable) token string and holds the parsed
    // {@link Claims}. We ALWAYS re-check expiration against the wall clock
    // before returning a hit — so a token that expired in cache never
    // authenticates. Entries are also bounded to {@value #MAX_CACHE_SIZE}
    // total and capped at {@value #SOFT_CACHE_TTL_MS} ms regardless of the
    // token's own {@code exp}, so a JWT-secret rotation takes effect within
    // that window even though we don't have explicit revocation.
    // -----------------------------------------------------------------------
    private static final int MAX_CACHE_SIZE = 5_000;

    /** Hard cap on how long we'll trust a cached parse, regardless of token {@code exp}. */
    private static final long SOFT_CACHE_TTL_MS = 5L * 60L * 1_000L; // 5 minutes

    private final Map<String, CachedClaims> claimsCache = new ConcurrentHashMap<>();

    private record CachedClaims(Claims claims, long expiresAtMs) {}

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(String username) {
        return generateToken(new HashMap<>(), username);
    }

    public String generateToken(Map<String, Object> extraClaims, String username) {
        return buildToken(extraClaims, username, jwtConfig.getJwtExpirationMs());
    }

    public String generateRefreshToken(String username) {
        return buildToken(new HashMap<>(), username, refreshExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        final Date now = new Date();
        final Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuer(jwtConfig.getJwtIssuer())
                .issuedAt(now)
                .notBefore(now) // Ensure token can't be used before now
                .expiration(expiryDate)
                .signWith(jwtSecretKey)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    public boolean isTokenValid(String token) {
        return !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        long now = System.currentTimeMillis();

        CachedClaims cached = claimsCache.get(token);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.claims();
        }
        if (cached != null) {
            // Stale entry — drop it before re-parsing so the cache doesn't grow forever
            // even when callers keep using the same expired token.
            claimsCache.remove(token, cached);
        }

        Claims claims;
        try {
            claims =
                    Jwts.parser()
                            .requireIssuer(jwtConfig.getJwtIssuer())
                            .verifyWith((SecretKey) jwtSecretKey)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();
        } catch (ExpiredJwtException ex) {
            log.warn("Expired JWT token: {}", ex.getMessage());
            throw new JwtAuthenticationException("Session expired. Please login again.", ex);
        } catch (UnsupportedJwtException | MalformedJwtException | SecurityException ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
            throw new JwtAuthenticationException("Invalid session. Please login again.", ex);
        } catch (Exception ex) {
            log.error("JWT token validation failed: {}", ex.getMessage());
            throw new JwtAuthenticationException(
                    "Session validation failed. Please login again.", ex);
        }

        // Cap the cache entry's lifetime at min(token-exp, SOFT_CACHE_TTL_MS) so a JWT-secret
        // rotation takes effect within {@value #SOFT_CACHE_TTL_MS} ms even without an explicit
        // revocation list. Skip caching outright if the cap is already in the past — and once
        // the cache is full, drop instead of evicting unpredictably (cleanup() runs on a timer).
        long tokenExp = claims.getExpiration() == null ? Long.MAX_VALUE : claims.getExpiration().getTime();
        long expiresAtMs = Math.min(tokenExp, now + SOFT_CACHE_TTL_MS);
        if (expiresAtMs > now && claimsCache.size() < MAX_CACHE_SIZE) {
            claimsCache.put(token, new CachedClaims(claims, expiresAtMs));
        }
        return claims;
    }

    /**
     * Periodic sweep: drop expired entries so the cache size stays bounded under steady-state
     * traffic. Runs once a minute — at 5k entries with 24-byte token strings + a Claims pointer
     * the cache fits comfortably in heap, so the sweep mostly exists to keep memory honest.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void cleanupExpiredClaims() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, CachedClaims>> it = claimsCache.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMs() <= now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Evicted {} expired JWT cache entries (size now {})", removed, claimsCache.size());
        }
    }

    // Custom exception for JWT authentication failures
    public static class JwtAuthenticationException extends RuntimeException {
        public JwtAuthenticationException(String message) {
            super(message);
        }

        public JwtAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
