package com.smmpanel.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.codec.Hex;

/**
 * Centralized security utilities for authentication and API key handling Consolidates scattered
 * security logic from multiple services
 */
public class SecurityUtils {

    /** Get current authenticated username */
    public static Optional<String> getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !authentication.getName().equals("anonymousUser")) {
            return Optional.of(authentication.getName());
        }
        return Optional.empty();
    }

    /** Get current username or throw exception */
    public static String getCurrentUsernameOrThrow() {
        return getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No authenticated user found"));
    }

    /**
     * Centralized API key hashing - consolidates duplicate implementations Uses consistent SHA-512
     * hashing across all services
     */
    public static String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hashedBytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return new String(Hex.encode(hashedBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 algorithm not available", e);
        }
    }

    /** Hash API key with salt for enhanced security */
    public static String hashApiKey(String apiKey, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            if (salt != null && !salt.isEmpty()) {
                digest.update(salt.getBytes(StandardCharsets.UTF_8));
            }
            byte[] hashedBytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return new String(Hex.encode(hashedBytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 algorithm not available", e);
        }
    }

    /** Verify API key against stored hash */
    public static boolean verifyApiKey(String apiKey, String storedHash) {
        String computedHash = hashApiKey(apiKey);
        return MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** Mask API key for logging (shows first 4 and last 4 chars) */
    public static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
