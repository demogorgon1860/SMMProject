package com.smmpanel.service;

import com.smmpanel.entity.User;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.util.ApiKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Service for managing API keys.
 * Handles generation, validation, and management of API keys for users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final UserRepository userRepository;
    private final ApiKeyGenerator apiKeyGenerator;
    private final PasswordEncoder passwordEncoder;

    /**
     * Generates a new API key for a user.
     * @param userId The ID of the user
     * @return The newly generated API key (only returned once)
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional
    public String generateApiKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        // Generate new API key and salt
        String apiKey = apiKeyGenerator.generateApiKey();
        String salt = apiKeyGenerator.generateSalt();
        
        try {
            // Hash the API key with the salt
            String hashedKey = apiKeyGenerator.hashApiKey(apiKey, salt);
            
            // Update user with new API key details
            user.setApiKeyHash(hashedKey);
            user.setApiKeySalt(salt);
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
     * @param apiKey The API key to validate
     * @param user The user to validate against
     * @return true if the API key is valid for the user, false otherwise
     */
    public boolean validateApiKey(String apiKey, User user) {
        if (user == null || user.getApiKeyHash() == null || user.getApiKeySalt() == null) {
            return false;
        }
        
        try {
            boolean isValid = apiKeyGenerator.verifyApiKey(apiKey, user.getApiKeyHash(), user.getApiKeySalt());
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
     * Revokes the API key for a user.
     * @param userId The ID of the user
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional
    public void revokeApiKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        
        user.setApiKeyHash(null);
        user.setApiKeySalt(null);
        user.setApiKeyLastRotated(null);
        userRepository.save(user);
        
        log.info("Revoked API key for user: {}", user.getUsername());
    }

    /**
     * Rotates the API key for a user.
     * @param userId The ID of the user
     * @return The new API key
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional
    public String rotateApiKey(Long userId) {
        // Revoke the old key first
        revokeApiKey(userId);
        // Generate and return a new key
        return generateApiKey(userId);
    }

    /**
     * Gets the masked API key for a user.
     * @param userId The ID of the user
     * @return The masked API key (first 4 and last 4 characters)
     * @throws ResourceNotFoundException if the user is not found
     */
    public String getMaskedApiKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
        
        if (user.getApiKeyHash() == null) {
            return "No API key set";
        }
        
        // Return a masked version of the key (first 4 and last 4 characters)
        return apiKeyGenerator.maskApiKey(user.getApiKeyHash());
    }
}
