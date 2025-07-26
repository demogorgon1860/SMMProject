package com.smmpanel.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;

public class SecurityUtils {
    public static Optional<String> getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && 
            !authentication.getName().equals("anonymousUser")) {
            return Optional.of(authentication.getName());
        }
        return Optional.empty();
    }
    public static String getCurrentUsernameOrThrow() {
        return getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No authenticated user found"));
    }
} 