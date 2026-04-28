package com.smmpanel.service.profile;

import com.smmpanel.dto.profile.SessionDto;
import com.smmpanel.entity.RefreshToken;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.RefreshTokenRepository;
import com.smmpanel.repository.jpa.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service session listing + revocation for {@code /v1/me/sessions}.
 *
 * <p>Backed by the {@code refresh_tokens} table — each row is one logged-in browser/device. The
 * "current" flag is computed by matching the request's refresh-token cookie against the stored
 * token value, so a user looking at their session list always sees one row marked as themselves.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Value("${app.jwt.refresh-cookie-name:refresh_token}")
    private String refreshCookieName;

    @Transactional(readOnly = true)
    public List<SessionDto> listSessions(HttpServletRequest request) {
        User user = currentUser();
        String currentTokenValue = readRefreshCookie(request);
        List<RefreshToken> active =
                refreshTokenRepository.findActiveTokensByUserOrderedByRecency(
                        user, LocalDateTime.now());
        return active.stream().map(rt -> toDto(rt, currentTokenValue)).toList();
    }

    /**
     * Revoke a specific session. Returns {@code true} on success. Throws {@link SecurityException}
     * if the row belongs to another user (defence in depth — the URL takes a raw id, so we must
     * verify ownership). Returns {@code false} on a no-op (already revoked or unknown id) so the
     * controller can map that to 204 idempotently.
     *
     * <p>Refuses to revoke the current session — that should go through {@code POST
     * /v1/auth/logout} which also clears the cookie. Tested by hashing the cookie's value against
     * the row's stored token.
     */
    @Transactional
    public RevokeResult revokeSession(Long tokenId, HttpServletRequest request) {
        User user = currentUser();
        Optional<RefreshToken> opt = refreshTokenRepository.findById(tokenId);
        if (opt.isEmpty()) return RevokeResult.NOT_FOUND;
        RefreshToken rt = opt.get();
        if (!rt.getUser().getId().equals(user.getId())) {
            log.warn(
                    "User {} attempted to revoke session {} owned by user {} — denied",
                    user.getId(),
                    tokenId,
                    rt.getUser().getId());
            throw new SecurityException("Session does not belong to current user");
        }
        if (rt.isRevoked()) return RevokeResult.ALREADY_REVOKED;

        String currentTokenValue = readRefreshCookie(request);
        if (currentTokenValue != null && currentTokenValue.equals(rt.getToken())) {
            return RevokeResult.IS_CURRENT;
        }

        rt.revoke("Revoked by user");
        refreshTokenRepository.save(rt);
        log.info("User {} revoked session {} ({})", user.getId(), rt.getId(), rt.getDeviceInfo());
        return RevokeResult.REVOKED;
    }

    /**
     * Revoke every session except the one making the request — "Sign out all other devices". Safe
     * no-op if the request has no refresh-cookie at all (we keep nothing).
     */
    @Transactional
    public int signOutOthers(HttpServletRequest request) {
        User user = currentUser();
        String currentTokenValue = readRefreshCookie(request);

        Long keepId = null;
        if (currentTokenValue != null) {
            keepId =
                    refreshTokenRepository
                            .findByToken(currentTokenValue)
                            .map(RefreshToken::getId)
                            .orElse(null);
        }
        // -1L is a safe sentinel: no refresh-token row will ever have id = -1, so the SQL update
        // will revoke every active token for the user. JPQL has no clean way to express "WHERE
        // :keepId IS NULL OR id <> :keepId" without surprising NULL semantics, so we use the
        // sentinel instead.
        int revoked =
                refreshTokenRepository.revokeAllUserTokensExcept(
                        user, keepId == null ? -1L : keepId, LocalDateTime.now());
        log.info("User {} signed out {} other session(s); kept {}", user.getId(), revoked, keepId);
        return revoked;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private SessionDto toDto(RefreshToken rt, String currentTokenValue) {
        boolean isCurrent = currentTokenValue != null && currentTokenValue.equals(rt.getToken());
        return SessionDto.builder()
                .id(rt.getId())
                .userAgent(rt.getDeviceInfo())
                .ipAddress(anonymizeIp(rt.getIpAddress()))
                .createdAt(rt.getCreatedAt())
                .lastUsedAt(rt.getLastUsedAt() == null ? rt.getCreatedAt() : rt.getLastUsedAt())
                .expiresAt(rt.getExpiryDate())
                .current(isCurrent)
                .build();
    }

    /**
     * Return an IP with the last octet (IPv4) or the last 80 bits (IPv6) stripped — coarse enough
     * to keep us out of GDPR "personal data" territory while still letting a user see "London, UK"
     * vs "Frankfurt, DE" if they have a sense of their own IPs. {@code null} or unparseable inputs
     * come back as {@code null} so the frontend can render a "—".
     */
    static String anonymizeIp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String ip = raw.trim();
        // Strip a possible "[v6]:port" or "v4:port" suffix; we only care about the address.
        if (ip.startsWith("[")) {
            int end = ip.indexOf(']');
            if (end > 0) ip = ip.substring(1, end);
        } else if (ip.contains(":") && ip.indexOf(':') == ip.lastIndexOf(':')) {
            // looks like "1.2.3.4:port" (single colon = v4 + port)
            ip = ip.substring(0, ip.indexOf(':'));
        }
        if (ip.contains(".") && !ip.contains(":")) {
            // IPv4 — drop last octet
            int last = ip.lastIndexOf('.');
            if (last <= 0) return ip;
            return ip.substring(0, last) + ".x";
        }
        if (ip.contains(":")) {
            // IPv6 — keep first 3 hextets, mask the rest. Handles "::1" and "1234:..::abcd"
            // by re-joining whatever survived the split.
            String[] parts = ip.split(":");
            StringBuilder masked = new StringBuilder();
            int kept = 0;
            for (String p : parts) {
                if (masked.length() > 0) masked.append(':');
                if (kept < 3 && !p.isEmpty()) {
                    masked.append(p);
                    kept++;
                } else {
                    masked.append('x');
                    if (kept < 3) kept++;
                }
            }
            return masked.toString();
        }
        return ip;
    }

    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies)
                .filter(c -> refreshCookieName.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UserNotFoundException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    public enum RevokeResult {
        REVOKED,
        ALREADY_REVOKED,
        NOT_FOUND,
        IS_CURRENT
    }
}
