package com.smmpanel.service.auth;

import com.smmpanel.entity.User;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.security.AuthenticationRateLimitService;
import com.smmpanel.util.ApiKeyGenerator;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing API keys. Handles generation, validation, and management of API keys for
 * users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final UserRepository userRepository;
    private final ApiKeyGenerator apiKeyGenerator;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationRateLimitService rateLimitService;

    @Value("${app.security.api-key.global-salt:smm-panel-secure-salt-2024}")
    private String globalSalt;

    /**
     * Generates a new API key for a user.
     *
     * @param userId The ID of the user
     * @return The newly generated API key (only returned once)
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional
    public String generateApiKey(Long userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId));

        // Generate new API key and salt
        String apiKey = apiKeyGenerator.generateApiKey();
        String salt = apiKeyGenerator.generateSalt();

        try {
            // Hash the API key with the salt
            String hashedKey = apiKeyGenerator.hashApiKey(apiKey, salt);

            // Create preview (first 4 + last 4 characters) for display
            String preview = apiKeyGenerator.maskApiKey(apiKey);

            // Update user with new API key details
            user.setApiKeyHash(hashedKey);
            user.setApiKeySalt(salt);
            user.setApiKeyPreview(preview);
            user.setApiKeyActive(true);
            user.setApiKeyLastRotated(LocalDateTime.now());
            userRepository.save(user);

            log.info("Generated new API key for user: {}", user.getUsername());
            return apiKey; // Return the plain API key (only time it's available)

        } catch (Exception e) {
            log.error("Failed to generate API key for user: {}", user.getUsername(), e);
            throw new ApiException("Failed to generate API key", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Validates an API key for a user.
     *
     * @param apiKey The API key to validate
     * @param user The user to validate against
     * @return true if the API key is valid for the user, false otherwise
     */
    public boolean validateApiKey(String apiKey, User user) {
        if (user == null || user.getApiKeyHash() == null || user.getApiKeySalt() == null) {
            return false;
        }

        // Check if API key is active
        if (user.getApiKeyActive() == null || !user.getApiKeyActive()) {
            log.warn(
                    "API key validation failed - key is inactive for user: {}", user.getUsername());
            return false;
        }

        try {
            boolean isValid =
                    apiKeyGenerator.verifyApiKey(
                            apiKey, user.getApiKeyHash(), user.getApiKeySalt());
            if (isValid) {
                // Update last used timestamp
                user.recordApiAccess();
                userRepository.save(user);
            }
            return isValid;
        } catch (Exception e) {
            log.error("Error validating API key for user: {}", user.getUsername(), e);
            return false;
        }
    }

    /**
     * Rotates the API key for a user. Deactivates the old key and generates a new active one.
     *
     * @param userId The ID of the user
     * @return The new API key
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional
    public String rotateApiKey(Long userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId));

        // Deactivate old API key before generating new one
        if (user.getApiKeyHash() != null) {
            user.setApiKeyActive(false);
            userRepository.save(user);
            log.info("Deactivated old API key for user: {}", user.getUsername());
        }

        // Generate new active key
        return generateApiKey(userId);
    }

    /**
     * Gets the masked API key for a user.
     *
     * @param userId The ID of the user
     * @return The masked API key (first 4 and last 4 characters)
     * @throws ResourceNotFoundException if the user is not found
     */
    public String getMaskedApiKey(Long userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId));

        if (user.getApiKeyHash() == null) {
            return "No API key set";
        }

        // Return the stored preview (first 4 and last 4 characters of actual API key)
        return user.getApiKeyPreview() != null ? user.getApiKeyPreview() : "Preview not available";
    }

    /**
     * SECURITY ENHANCED: Hash API key for database lookup using global salt This prevents rainbow
     * table attacks while maintaining lookup performance
     *
     * @param apiKey The API key to hash for lookup
     * @return The hashed API key for database lookup
     */
    public String hashApiKeyForLookup(String apiKey) {
        try {
            // Use global salt for consistent lookup hashing
            return apiKeyGenerator.hashApiKey(apiKey, globalSalt);
        } catch (Exception e) {
            log.error("Error hashing API key for lookup: {}", e.getMessage());
            throw new RuntimeException("Failed to hash API key for lookup", e);
        }
    }

    /**
     * SECURITY ENHANCED: Validates an API key with rate limiting and constant-time comparison Used
     * in authentication filters to avoid blocking the hot path
     *
     * @param apiKey The API key to validate
     * @param apiKeyHash The stored hash to validate against
     * @param apiKeySalt The salt used for hashing
     * @param clientIdentifier Identifier for rate limiting (IP or API key prefix)
     * @return true if the API key is valid, false otherwise
     */
    public boolean verifyApiKeyOnly(
            String apiKey, String apiKeyHash, String apiKeySalt, String clientIdentifier) {
        // Check rate limiting first
        if (rateLimitService.isRateLimited(clientIdentifier)) {
            log.warn(
                    "API key validation blocked due to rate limiting for identifier: {}",
                    maskString(clientIdentifier));
            return false;
        }

        if (apiKeyHash == null || apiKeySalt == null) {
            rateLimitService.recordFailedAttempt(clientIdentifier);
            return false;
        }

        try {
            boolean isValid = apiKeyGenerator.verifyApiKey(apiKey, apiKeyHash, apiKeySalt);

            if (isValid) {
                rateLimitService.recordSuccessfulAttempt(clientIdentifier);
                log.debug(
                        "API key validation successful for identifier: {}",
                        maskString(clientIdentifier));
            } else {
                rateLimitService.recordFailedAttempt(clientIdentifier);
                log.warn(
                        "API key validation failed for identifier: {}",
                        maskString(clientIdentifier));
            }

            return isValid;
        } catch (Exception e) {
            log.error(
                    "Error verifying API key for identifier: {}", maskString(clientIdentifier), e);
            rateLimitService.recordFailedAttempt(clientIdentifier);
            return false;
        }
    }

    /**
     * BACKWARD COMPATIBILITY: Validates an API key without rate limiting
     *
     * @deprecated Use verifyApiKeyOnly(apiKey, hash, salt, identifier) for enhanced security
     */
    @Deprecated
    public boolean verifyApiKeyOnly(String apiKey, String apiKeyHash, String apiKeySalt) {
        if (apiKeyHash == null || apiKeySalt == null) {
            return false;
        }

        try {
            return apiKeyGenerator.verifyApiKey(apiKey, apiKeyHash, apiKeySalt);
        } catch (Exception e) {
            log.error("Error verifying API key: {}", e.getMessage());
            return false;
        }
    }

    /** Utility method to safely mask strings for logging */
    private String maskString(String input) {
        if (input == null || input.length() <= 4) {
            return "[masked]";
        }
        return input.substring(0, 2) + "***" + input.substring(input.length() - 2);
    }
}
