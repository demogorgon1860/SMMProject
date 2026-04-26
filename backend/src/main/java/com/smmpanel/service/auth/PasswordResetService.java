package com.smmpanel.service.auth;

import com.smmpanel.entity.PasswordResetToken;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.PasswordResetTokenRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.RefreshTokenService;
import com.smmpanel.service.email.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset flow — request a one-time link by email, then redeem the link to set a new
 * password.
 *
 * <p>Security properties:
 *
 * <ul>
 *   <li><strong>No account enumeration.</strong> {@link #requestReset} always succeeds visually,
 *       even when the email is unknown.
 *   <li>Tokens are 256-bit URL-safe random strings; only their SHA-256 hash is stored.
 *   <li>Tokens expire after a short window (1h default) and become {@code usedAt}=now after a
 *       single successful use.
 *   <li>Per-user rate limit prevents spamming reset emails.
 *   <li>Successful reset revokes ALL refresh tokens — outstanding sessions on other devices are
 *       invalidated.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.auth.password-reset.ttl-hours:1}")
    private int ttlHours;

    @Value("${app.auth.forgot-password.rate-limit-per-hour:5}")
    private int rateLimitPerHour;

    /**
     * Always returns silently. Either a reset email is queued, or the email doesn't match a real
     * account (and we pretend it does). The frontend renders the same success state either way.
     */
    @Transactional
    public void requestReset(String email, HttpServletRequest request) {
        if (email == null || email.isBlank()) return;

        Optional<User> userOpt = userRepository.findByEmail(email.trim().toLowerCase());
        if (userOpt.isEmpty() || !userOpt.get().isActive()) {
            log.info("Password reset requested for unknown/inactive email — pretending OK");
            return;
        }
        User user = userOpt.get();

        long recent = tokenRepository.countRecentForUser(user.getId(), LocalDateTime.now().minusHours(1));
        if (recent >= rateLimitPerHour) {
            log.warn(
                    "Password reset rate limit hit for user {} ({} requests in last hour)",
                    user.getId(),
                    recent);
            return;
        }

        String rawToken = generateToken();
        PasswordResetToken token =
                PasswordResetToken.builder()
                        .userId(user.getId())
                        .tokenHash(EmailVerificationService.sha256(rawToken))
                        .expiresAt(LocalDateTime.now().plus(Duration.ofHours(ttlHours)))
                        .ip(extractIp(request))
                        .userAgent(extractUserAgent(request))
                        .build();
        tokenRepository.save(token);

        emailService.sendPasswordResetLink(user.getEmail(), user.getUsername(), rawToken);
        log.info("Password reset link sent to user {}", user.getId());
    }

    /**
     * Redeem a reset token and set a new password. Throws on invalid token, expired token, or
     * already-used token. Also revokes all refresh tokens on success.
     */
    @Transactional
    public void redeem(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Reset token is required");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        PasswordResetToken token =
                tokenRepository
                        .findByTokenHash(EmailVerificationService.sha256(rawToken))
                        .orElseThrow(
                                () -> new IllegalArgumentException("Invalid or expired reset link"));

        if (token.isUsed() || token.isExpired()) {
            throw new IllegalArgumentException("Invalid or expired reset link");
        }

        User user =
                userRepository
                        .findById(token.getUserId())
                        .orElseThrow(() -> new IllegalArgumentException("Account not found"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(LocalDateTime.now());
        tokenRepository.save(token);
        // Belt-and-braces: invalidate any other unused tokens for this user.
        tokenRepository.markAllUsedForUser(user.getId(), LocalDateTime.now());

        // Force re-authentication on every device.
        try {
            refreshTokenService.revokeAllUserTokens(user, "Password reset");
        } catch (Exception e) {
            log.warn("Could not revoke refresh tokens after reset for user {}: {}", user.getId(), e.toString());
        }

        log.info("Password reset completed for user {}", user.getId());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String generateToken() {
        byte[] buf = new byte[32];
        SECURE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String extractIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private String extractUserAgent(HttpServletRequest request) {
        if (request == null) return null;
        String ua = request.getHeader("User-Agent");
        if (ua == null) return null;
        return ua.length() > 500 ? ua.substring(0, 500) : ua;
    }
}
