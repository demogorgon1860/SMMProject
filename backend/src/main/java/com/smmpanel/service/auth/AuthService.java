package com.smmpanel.service.auth;

import com.smmpanel.dto.UserDto;
import com.smmpanel.dto.auth.*;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.security.JwtService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailVerificationService emailVerificationService;

    /**
     * Names we never let a regular user claim. Most of these collide with display fallbacks the
     * frontend uses when {@code user.username} is empty (so a real account with that name would be
     * impersonating "no name") or with role-implying labels.
     */
    private static final java.util.Set<String> RESERVED_USERNAMES =
            java.util.Set.of(
                    "user",
                    "users",
                    "admin",
                    "administrator",
                    "root",
                    "support",
                    "moderator",
                    "operator",
                    "system",
                    "smmworld",
                    "official",
                    "test",
                    "anonymous",
                    "you",
                    "me",
                    "guest",
                    "null",
                    "undefined");

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if user already exists
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }

        if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }

        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("Password is required");
        }

        // Normalize username and email to lowercase to match @PrePersist behavior
        String normalizedUsername = request.getUsername().trim().toLowerCase();
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // Username shape: 3-32 chars, only [a-z0-9._-]. Without this, names like "User"
        // or " " or "@@@" sailed through and showed up as account labels everywhere.
        if (!normalizedUsername.matches("^[a-z0-9._-]{3,32}$")) {
            throw new IllegalArgumentException(
                    "Username must be 3-32 characters: lowercase letters, digits, dot, dash or"
                            + " underscore");
        }
        // Reject names that map onto our display fallbacks or imply privileged roles.
        if (RESERVED_USERNAMES.contains(normalizedUsername)) {
            throw new IllegalArgumentException("This username is reserved — pick another one");
        }

        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("Username already exists");
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("Email already registered");
        }

        // Create new user using builder to ensure proper initialization
        User user =
                User.builder()
                        .username(request.getUsername())
                        .email(request.getEmail())
                        .passwordHash(passwordEncoder.encode(request.getPassword()))
                        .role(UserRole.USER)
                        .apiKeyHash(hashApiKey(generateApiKey()))
                        .balance(java.math.BigDecimal.ZERO)
                        .isActive(true)
                        .emailVerified(false)
                        .build();

        user = userRepository.save(user);

        log.info("New user registered: {}", user.getUsername());

        // Issue + email a verification code. The frontend redirects to /verify-email
        // immediately after a successful POST /auth/register, expecting the code to be
        // in the user's inbox by the time they paste it. This call had been missing
        // entirely — registration created the account but never sent any email, so users
        // would sit on the verify-email page forever or come back later assuming they'd
        // never registered at all.
        //
        // Wrapped in try/catch so a Resend outage doesn't roll back the whole registration —
        // the user can hit Resend on the verify page once email is back up.
        try {
            emailVerificationService.issueCodeFor(user);
        } catch (Exception e) {
            log.error(
                    "Failed to issue verification code for new user {} ({}): {}",
                    user.getId(),
                    user.getEmail(),
                    e.getMessage(),
                    e);
        }

        // Generate tokens
        String accessToken = jwtService.generateToken(user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .user(mapToDto(user))
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        // Normalize username to lowercase to match stored format
        String normalizedUsername = request.getUsername().trim().toLowerCase();
        log.debug("Login attempt for user: {}", normalizedUsername);

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            normalizedUsername, request.getPassword()));
        } catch (Exception e) {
            log.error("Authentication failed for user {}: {}", normalizedUsername, e.getMessage());
            throw e;
        }

        User user =
                userRepository
                        .findByUsername(normalizedUsername)
                        .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Belt-and-braces: CustomUserDetailsService already throws on soft-deleted, but a
        // misordered filter chain or future refactor could route past it. The deletion flag is
        // the single source of truth — never issue a token for a deleted account.
        if (user.isSoftDeleted()) {
            log.warn("Blocked login for soft-deleted user {}", user.getId());
            throw new UserNotFoundException("User not found");
        }

        log.debug("User found: {}, active: {}", user.getUsername(), user.isActive());

        String accessToken = jwtService.generateToken(user.getUsername());
        String refreshToken = jwtService.generateRefreshToken(user.getUsername());

        log.info("Login successful for user: {}", request.getUsername());

        return AuthResponse.builder()
                .token(accessToken)
                .refreshToken(refreshToken)
                .user(mapToDto(user))
                .build();
    }

    public UserDto getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // /api/v*/auth/** is currently permitAll() in SecurityConfig — anonymous requests reach
        // this method with either a null Authentication or AnonymousAuthenticationToken (name
        // "anonymousUser"). Without this guard the previous behaviour was an NPE / a lookup
        // for a user named "anonymousUser" → UserNotFoundException → blanket 500. Returning
        // 401 here matches the contract any client expects for "tell me about the current
        // user" and lets the frontend's silent .catch handlers stop logging server errors.
        if (auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        String username = auth.getName();
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new UserNotFoundException("User not found"));

        return mapToDto(user);
    }

    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String username = jwtService.extractUsername(request.getRefreshToken());

        if (username != null && jwtService.isTokenValid(request.getRefreshToken())) {
            String newAccessToken = jwtService.generateToken(username);

            return TokenResponse.builder()
                    .accessToken(newAccessToken)
                    .refreshToken(request.getRefreshToken())
                    .build();
        }

        throw new RuntimeException("Invalid refresh token");
    }

    private String generateApiKey() {
        return "sk_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String hashApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    private UserDto mapToDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .balance(user.getBalance().toString())
                .role(user.getRole().name())
                .apiKey(null) // API key should not be exposed after registration
                .build();
    }
}
