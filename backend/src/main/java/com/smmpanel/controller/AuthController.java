package com.smmpanel.controller;

import com.smmpanel.dto.UserDto;
import com.smmpanel.dto.auth.*;
import com.smmpanel.entity.RefreshToken;
import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.security.JwtService;
import com.smmpanel.service.auth.AuthService;
import com.smmpanel.service.auth.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Value("${app.jwt.refresh-cookie-name:refresh_token}")
    private String refreshCookieName;

    @Value("${app.jwt.refresh-expiration-days:7}")
    private long refreshTokenDurationDays;

    @Value("${app.jwt.cookie-domain:}")
    private String cookieDomain;

    @Value("${app.jwt.cookie-secure:true}")
    private boolean cookieSecure;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        AuthResponse authResponse = authService.register(request);

        // Create refresh token with HttpOnly cookie support
        User user =
                userRepository
                        .findByUsername(request.getUsername())
                        .orElseThrow(() -> new UserNotFoundException("User not found"));
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, httpRequest);

        // Set refresh token as HttpOnly cookie
        setRefreshTokenCookie(response, refreshToken.getToken());

        // Keep backward compatibility but also set cookie
        authResponse.setRefreshToken(refreshToken.getToken());

        log.info("User registered successfully: {}", request.getUsername());
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        AuthResponse authResponse = authService.login(request);

        // Create refresh token with HttpOnly cookie support
        User user =
                userRepository
                        .findByUsername(request.getUsername())
                        .orElseThrow(() -> new UserNotFoundException("User not found"));
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user, httpRequest);

        // Set refresh token as HttpOnly cookie
        setRefreshTokenCookie(response, refreshToken.getToken());

        // Keep backward compatibility but also set cookie
        authResponse.setRefreshToken(refreshToken.getToken());

        log.info("User logged in successfully: {}", request.getUsername());
        return ResponseEntity.ok(authResponse);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser() {
        return ResponseEntity.ok(authService.getCurrentUser());
    }

    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getUserProfile() {
        UserDto user = authService.getCurrentUser();

        // Enhanced profile response with additional details
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId());
        profile.put("username", user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("role", user.getRole());
        profile.put("balance", user.getBalance());
        profile.put("isActive", true); // All logged in users are active
        profile.put("createdAt", null); // These fields are not in UserDto
        profile.put("updatedAt", null);

        // Add API key status if available
        User fullUser = userRepository.findById(user.getId()).orElse(null);
        if (fullUser != null) {
            profile.put("hasApiKey", fullUser.getApiKeyHash() != null);
            profile.put("apiKeyLastRotated", fullUser.getApiKeyLastRotated());
        }

        return ResponseEntity.ok(profile);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refreshToken(
            @RequestBody(required = false) RefreshTokenRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        // Try to get refresh token from cookie first, then from request body
        String refreshTokenValue = getRefreshTokenFromCookie(httpRequest);
        if (refreshTokenValue == null && request != null) {
            refreshTokenValue = request.getRefreshToken();
        }

        if (refreshTokenValue == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            // Verify and rotate refresh token
            String newRefreshToken =
                    refreshTokenService.rotateRefreshToken(refreshTokenValue, httpRequest);

            // Get user from old token
            RefreshToken oldToken =
                    refreshTokenService
                            .findByToken(refreshTokenValue)
                            .orElseThrow(() -> new RuntimeException("Token not found"));

            // Generate new access token
            String newAccessToken = jwtService.generateToken(oldToken.getUser().getUsername());

            // Set new refresh token cookie
            setRefreshTokenCookie(response, newRefreshToken);

            TokenResponse tokenResponse =
                    TokenResponse.builder()
                            .accessToken(newAccessToken)
                            .refreshToken(newRefreshToken) // Keep backward compatibility
                            .build();

            return ResponseEntity.ok(tokenResponse);
        } catch (Exception e) {
            log.error("Error refreshing token: {}", e.getMessage());
            clearRefreshTokenCookie(response);
            return ResponseEntity.status(401).build();
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {

        // Get current user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String username = auth.getName();

            // Get refresh token from cookie
            String refreshToken = getRefreshTokenFromCookie(request);

            if (refreshToken != null) {
                // Revoke the refresh token
                refreshTokenService.revokeToken(refreshToken, "User logout");
            }

            // Clear the cookie
            clearRefreshTokenCookie(response);

            log.info("User logged out successfully: {}", username);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(HttpServletResponse response) {
        // Get current user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String username = auth.getName();
            User user =
                    userRepository
                            .findByUsername(username)
                            .orElseThrow(() -> new UserNotFoundException("User not found"));

            // Revoke all user's refresh tokens
            refreshTokenService.revokeAllUserTokens(user, "Logout from all devices");

            // Clear the cookie
            clearRefreshTokenCookie(response);

            log.info("User logged out from all devices: {}", username);
        }

        return ResponseEntity.ok().build();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie =
                ResponseCookie.from(refreshCookieName, token)
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .sameSite("Strict")
                        .path("/api/v1/auth")
                        .maxAge(Duration.ofDays(refreshTokenDurationDays))
                        .domain(cookieDomain.isEmpty() ? null : cookieDomain)
                        .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie =
                ResponseCookie.from(refreshCookieName, "")
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .sameSite("Strict")
                        .path("/api/v1/auth")
                        .maxAge(0)
                        .domain(cookieDomain.isEmpty() ? null : cookieDomain)
                        .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String getRefreshTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            Optional<Cookie> refreshCookie =
                    Arrays.stream(cookies)
                            .filter(cookie -> refreshCookieName.equals(cookie.getName()))
                            .findFirst();

            return refreshCookie.map(Cookie::getValue).orElse(null);
        }
        return null;
    }
}
