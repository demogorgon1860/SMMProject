package com.smmpanel.service.profile;

import com.smmpanel.dto.profile.ChangePasswordRequest;
import com.smmpanel.dto.profile.ProfileMeResponse;
import com.smmpanel.dto.profile.UpdateProfileRequest;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserNotificationPrefs;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.UserNotificationPrefsRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.RefreshTokenService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Self-service profile operations. The current user is resolved from the SecurityContext. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final UserNotificationPrefsRepository prefsRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @Transactional(readOnly = true)
    public ProfileMeResponse getMe() {
        User user = currentUser();
        return toResponse(user);
    }

    @Transactional
    public ProfileMeResponse updateMe(UpdateProfileRequest request) {
        User user = currentUser();
        boolean changed = false;

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            String desired = request.getUsername().trim().toLowerCase();
            if (!desired.equals(user.getUsername())) {
                if (userRepository.existsByUsername(desired)) {
                    throw new IllegalArgumentException("Username already taken");
                }
                user.setUsername(desired);
                changed = true;
            }
        }
        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            user.setTimezone(request.getTimezone().trim());
            changed = true;
        }

        if (changed) userRepository.save(user);
        return toResponse(user);
    }

    /**
     * Change the password. Requires the current password to match. Revokes all refresh tokens so
     * other devices have to re-authenticate.
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = currentUser();
        if (!passwordEncoder.matches(request.getCurrent(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        if (passwordEncoder.matches(request.getNext(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNext()));
        userRepository.save(user);

        try {
            refreshTokenService.revokeAllUserTokens(user, "Password changed by user");
        } catch (Exception e) {
            log.warn("Could not revoke refresh tokens for user {}: {}", user.getId(), e.toString());
        }
        log.info("User {} changed password", user.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Boolean> getNotifications() {
        User user = currentUser();
        return prefsRepository
                .findById(user.getId())
                .map(UserNotificationPrefs::getPrefs)
                .orElseGet(UserNotificationPrefs::defaults);
    }

    @Transactional
    public Map<String, Boolean> updateNotifications(Map<String, Boolean> patch) {
        if (patch == null) patch = Map.of();
        User user = currentUser();

        UserNotificationPrefs prefs =
                prefsRepository
                        .findById(user.getId())
                        .orElseGet(
                                () ->
                                        UserNotificationPrefs.builder()
                                                .userId(user.getId())
                                                .prefs(UserNotificationPrefs.defaults())
                                                .build());

        Map<String, Boolean> merged = new LinkedHashMap<>(prefs.getPrefs());
        // Only allow keys we know about — drops typos and prevents storage bloat.
        for (Map.Entry<String, Boolean> entry : patch.entrySet()) {
            if (UserNotificationPrefs.defaults().containsKey(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue() != null && entry.getValue());
            }
        }
        prefs.setPrefs(merged);
        prefsRepository.save(prefs);
        return merged;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new UserNotFoundException("Not authenticated");
        }
        return userRepository
                .findByUsername(auth.getName())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    private static ProfileMeResponse toResponse(User user) {
        return ProfileMeResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole() == null ? null : user.getRole().name())
                .balance(user.getBalance())
                .active(user.isActive())
                .emailVerified(user.isEmailVerified())
                .emailVerifiedAt(user.getEmailVerifiedAt())
                .twoFactorEnabled(user.isTwoFactorEnabled())
                .timezone(user.getTimezone())
                .apiKeyConfigured(user.getApiKeyHash() != null)
                .apiKeyLastRotated(user.getApiKeyLastRotated())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }
}
