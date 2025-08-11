package com.smmpanel.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Component;

/**
 * Utility class for generating and hashing API keys. Uses cryptographically secure random number
 * generation.
 */
@Component
public class ApiKeyGenerator {

    private static final int API_KEY_LENGTH = 64;
    private static final int SALT_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Generates a new random API key.
     *
     * @return A securely generated API key string
     */
    public String generateApiKey() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder apiKey = new StringBuilder(API_KEY_LENGTH);

        for (int i = 0; i < API_KEY_LENGTH; i++) {
            int index = SECURE_RANDOM.nextInt(chars.length());
            apiKey.append(chars.charAt(index));
        }

        return apiKey.toString();
    }

    /**
     * Generates a random salt for hashing the API key.
     *
     * @return A base64 encoded salt string
     */
    public String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * Hashes an API key with the provided salt.
     *
     * @param apiKey The API key to hash
     * @param salt The salt to use for hashing
     * @return The hashed API key as a hex string
     * @throws NoSuchAlgorithmException If SHA-512 is not available
     */
    public String hashApiKey(String apiKey, String salt) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
        digest.update(saltBytes);
        byte[] hashedBytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
        return new String(Hex.encode(hashedBytes));
    }

    /**
     * Verifies an API key against a stored hash and salt.
     *
     * @param apiKey The API key to verify
     * @param storedHash The stored hash to compare against
     * @param salt The salt used for the stored hash
     * @return true if the API key matches the stored hash, false otherwise
     */
    public boolean verifyApiKey(String apiKey, String storedHash, String salt) {
        try {
            String computedHash = hashApiKey(apiKey, salt);
            return MessageDigest.isEqual(
                    computedHash.getBytes(StandardCharsets.UTF_8),
                    storedHash.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to verify API key: " + e.getMessage(), e);
        }
    }

    /**
     * Masks an API key for logging purposes. Shows only the first 4 and last 4 characters.
     *
     * @param apiKey The API key to mask
     * @return A masked version of the API key
     */
    public String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "[hidden]";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
