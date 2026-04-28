package com.smmpanel.service.profile;

import com.smmpanel.entity.User;
import com.smmpanel.exception.UserNotFoundException;
import com.smmpanel.repository.jpa.UserRepository;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service API-key pause/resume. Pausing flips {@code users.api_key_paused_at} to NOW; resume
 * nulls it. The {@link com.smmpanel.security.ApiKeyAuthenticationFilter} reads the column and
 * rejects requests whose authenticated user has it set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyPauseService {

    private final UserRepository userRepository;

    @Transactional
    public Map<String, Object> pause() {
        User user = currentUser();
        if (user.getApiKeyPausedAt() == null) {
            user.setApiKeyPausedAt(LocalDateTime.now());
            userRepository.save(user);
            log.info(
                    "User {} paused their API key (audit: self-service pause)", user.getUsername());
        }
        return status(user);
    }

    @Transactional
    public Map<String, Object> resume() {
        User user = currentUser();
        if (user.getApiKeyPausedAt() != null) {
            user.setApiKeyPausedAt(null);
            userRepository.save(user);
            log.info(
                    "User {} resumed their API key (audit: self-service resume)",
                    user.getUsername());
        }
        return status(user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatus() {
        return status(currentUser());
    }

    private static Map<String, Object> status(User user) {
        // LinkedHashMap (not Map.of) — Map.of rejects null values, and `pausedAt` is null when
        // the API key is active. JSON serialization preserves the null and the frontend reads
        // it as `null`, which is the right "not paused" sentinel.
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("paused", user.getApiKeyPausedAt() != null);
        m.put("pausedAt", user.getApiKeyPausedAt());
        return m;
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
}
