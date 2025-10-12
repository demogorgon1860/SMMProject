package com.smmpanel.service.auth;

import com.smmpanel.entity.RefreshToken;
import com.smmpanel.entity.User;
import com.smmpanel.exception.TokenRefreshException;
import com.smmpanel.repository.jpa.RefreshTokenRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    @Value("${app.jwt.refresh-expiration-days:7}")
    private long refreshTokenDurationDays;

    @Value("${app.jwt.max-refresh-tokens-per-user:5}")
    private int maxRefreshTokensPerUser;

    @Transactional
    public RefreshToken createRefreshToken(User user, HttpServletRequest request) {
        // Check if user has too many active tokens
        long activeTokenCount =
                refreshTokenRepository.countActiveTokensByUser(user, LocalDateTime.now());
        if (activeTokenCount >= maxRefreshTokensPerUser) {
            // Revoke oldest tokens
            revokeOldestTokens(user, activeTokenCount - maxRefreshTokensPerUser + 1);
        }

        RefreshToken refreshToken =
                RefreshToken.builder()
                        .token(generateRefreshToken())
                        .user(user)
                        .expiryDate(LocalDateTime.now().plusDays(refreshTokenDurationDays))
                        .ipAddress(extractIpAddress(request))
                        .deviceInfo(extractDeviceInfo(request))
                        .build();

        return refreshTokenRepository.save(refreshToken);
    }

    @Transactional
    public Optional<RefreshToken> findByToken(String token) {
        return refreshTokenRepository.findByTokenAndIsRevokedFalse(token);
    }

    @Transactional
    public RefreshToken verifyRefreshToken(String token) {
        RefreshToken refreshToken =
                refreshTokenRepository
                        .findByTokenAndIsRevokedFalse(token)
                        .orElseThrow(() -> new TokenRefreshException("Refresh token not found"));

        if (!refreshToken.isValid()) {
            if (refreshToken.isExpired()) {
                refreshToken.revoke("Token expired");
                refreshTokenRepository.save(refreshToken);
                throw new TokenRefreshException("Refresh token has expired");
            }
            throw new TokenRefreshException("Refresh token has been revoked");
        }

        return refreshToken;
    }

    @Transactional
    public void revokeToken(String token, String reason) {
        refreshTokenRepository
                .findByToken(token)
                .ifPresent(
                        refreshToken -> {
                            refreshToken.revoke(reason);
                            refreshTokenRepository.save(refreshToken);
                            log.info(
                                    "Revoked refresh token for user: {}, reason: {}",
                                    refreshToken.getUser().getUsername(),
                                    reason);
                        });
    }

    @Transactional
    public void revokeAllUserTokens(User user, String reason) {
        int revokedCount = refreshTokenRepository.revokeAllUserTokens(user, LocalDateTime.now());
        log.info("Revoked {} refresh tokens for user: {}", revokedCount, user.getUsername());
    }

    @Transactional
    public void revokeTokenById(Long tokenId, String reason) {
        refreshTokenRepository
                .findById(tokenId)
                .ifPresent(
                        refreshToken -> {
                            refreshToken.revoke(reason);
                            refreshTokenRepository.save(refreshToken);
                        });
    }

    @Transactional
    public String rotateRefreshToken(String oldToken, HttpServletRequest request) {
        RefreshToken oldRefreshToken = verifyRefreshToken(oldToken);

        // Revoke old token
        oldRefreshToken.revoke("Token rotation");
        refreshTokenRepository.save(oldRefreshToken);

        // Create new token
        RefreshToken newRefreshToken = createRefreshToken(oldRefreshToken.getUser(), request);

        log.info("Rotated refresh token for user: {}", oldRefreshToken.getUser().getUsername());
        return newRefreshToken.getToken();
    }

    @Scheduled(cron = "0 0 2 * * ?") // Run at 2 AM daily
    @Transactional
    public void cleanupExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();

        // Revoke expired tokens
        int expiredCount = refreshTokenRepository.revokeExpiredTokens(now);
        log.info("Revoked {} expired refresh tokens", expiredCount);

        // Delete old revoked tokens (older than 30 days)
        LocalDateTime cutoffDate = now.minusDays(30);
        int deletedCount = refreshTokenRepository.deleteOldRevokedTokens(cutoffDate);
        log.info("Deleted {} old revoked refresh tokens", deletedCount);
    }

    private void revokeOldestTokens(User user, long count) {
        refreshTokenRepository.findActiveTokensByUser(user, LocalDateTime.now()).stream()
                .limit(count)
                .forEach(
                        token -> {
                            token.revoke("Max token limit reached");
                            refreshTokenRepository.save(token);
                        });
    }

    private String generateRefreshToken() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String extractIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    private String extractDeviceInfo(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) {
            return "Unknown";
        }

        // Simple device detection
        if (userAgent.contains("Mobile")) {
            return "Mobile";
        } else if (userAgent.contains("Tablet")) {
            return "Tablet";
        } else {
            return "Desktop";
        }
    }

    public boolean isTokenValid(String token) {
        return refreshTokenRepository.existsByTokenAndIsRevokedFalse(token);
    }
}
