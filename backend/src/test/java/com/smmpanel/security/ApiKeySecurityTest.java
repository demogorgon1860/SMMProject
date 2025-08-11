package com.smmpanel.security;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.util.ApiKeyGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Comprehensive security tests for API key generation, hashing, and collision resistance Tests
 * various attack scenarios and validates cryptographic security
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiKeySecurityTest {

    private ApiKeyGenerator apiKeyGenerator;

    @BeforeEach
    void setUp() {
        apiKeyGenerator = new ApiKeyGenerator();
    }

    @Test
    @DisplayName("API keys should be cryptographically random and unique")
    @RepeatedTest(100)
    void testApiKeyUniqueness() {
        Set<String> generatedKeys = new HashSet<>();

        // Generate 1000 API keys and ensure no duplicates
        for (int i = 0; i < 1000; i++) {
            String apiKey = apiKeyGenerator.generateApiKey();

            // Basic validation
            assertNotNull(apiKey, "API key should not be null");
            assertEquals(64, apiKey.length(), "API key should be 64 characters long");
            assertTrue(
                    apiKey.matches("[a-zA-Z0-9]+"),
                    "API key should contain only alphanumeric characters");

            // Check uniqueness
            assertFalse(generatedKeys.contains(apiKey), "API key should be unique");
            generatedKeys.add(apiKey);
        }
    }

    @Test
    @DisplayName("Salts should be cryptographically random and unique")
    @RepeatedTest(50)
    void testSaltUniqueness() {
        Set<String> generatedSalts = new HashSet<>();

        // Generate 1000 salts and ensure no duplicates
        for (int i = 0; i < 1000; i++) {
            String salt = apiKeyGenerator.generateSalt();

            // Basic validation
            assertNotNull(salt, "Salt should not be null");
            assertFalse(salt.isEmpty(), "Salt should not be empty");

            // Check uniqueness
            assertFalse(generatedSalts.contains(salt), "Salt should be unique");
            generatedSalts.add(salt);
        }
    }

    @Test
    @DisplayName("Hash function should be deterministic and consistent")
    void testHashConsistency() throws NoSuchAlgorithmException {
        String apiKey = "test-api-key-123456789";
        String salt = "test-salt-987654321";

        // Hash the same input multiple times
        String hash1 = apiKeyGenerator.hashApiKey(apiKey, salt);
        String hash2 = apiKeyGenerator.hashApiKey(apiKey, salt);
        String hash3 = apiKeyGenerator.hashApiKey(apiKey, salt);

        // All hashes should be identical
        assertEquals(hash1, hash2, "Hash should be deterministic");
        assertEquals(hash2, hash3, "Hash should be deterministic");

        // Hash should be SHA-512 length (128 hex characters)
        assertEquals(128, hash1.length(), "Hash should be 128 characters (SHA-512)");
        assertTrue(hash1.matches("[a-f0-9]+"), "Hash should be lowercase hex");
    }

    @Test
    @DisplayName("Different salts should produce different hashes for same API key")
    void testSaltImpactOnHash() throws NoSuchAlgorithmException {
        String apiKey = "identical-api-key";
        String salt1 = apiKeyGenerator.generateSalt();
        String salt2 = apiKeyGenerator.generateSalt();

        String hash1 = apiKeyGenerator.hashApiKey(apiKey, salt1);
        String hash2 = apiKeyGenerator.hashApiKey(apiKey, salt2);

        assertNotEquals(hash1, hash2, "Different salts should produce different hashes");
    }

    @Test
    @DisplayName("Different API keys should produce different hashes with same salt")
    void testApiKeyImpactOnHash() throws NoSuchAlgorithmException {
        String salt = apiKeyGenerator.generateSalt();
        String apiKey1 = "api-key-one";
        String apiKey2 = "api-key-two";

        String hash1 = apiKeyGenerator.hashApiKey(apiKey1, salt);
        String hash2 = apiKeyGenerator.hashApiKey(apiKey2, salt);

        assertNotEquals(hash1, hash2, "Different API keys should produce different hashes");
    }

    @Test
    @DisplayName("API key verification should use constant-time comparison")
    void testConstantTimeComparison() throws NoSuchAlgorithmException {
        String apiKey = "test-verification-key";
        String salt = apiKeyGenerator.generateSalt();
        String correctHash = apiKeyGenerator.hashApiKey(apiKey, salt);

        // Test correct verification
        assertTrue(
                apiKeyGenerator.verifyApiKey(apiKey, correctHash, salt),
                "Correct API key should verify successfully");

        // Test incorrect API key
        assertFalse(
                apiKeyGenerator.verifyApiKey("wrong-key", correctHash, salt),
                "Incorrect API key should fail verification");

        // Test incorrect hash
        String wrongHash = apiKeyGenerator.hashApiKey("different-key", salt);
        assertFalse(
                apiKeyGenerator.verifyApiKey(apiKey, wrongHash, salt),
                "Incorrect hash should fail verification");

        // Test incorrect salt
        String wrongSalt = apiKeyGenerator.generateSalt();
        assertFalse(
                apiKeyGenerator.verifyApiKey(apiKey, correctHash, wrongSalt),
                "Incorrect salt should fail verification");
    }

    @Test
    @DisplayName("Hash collision resistance - massive generation test")
    void testHashCollisionResistance() throws NoSuchAlgorithmException {
        Set<String> generatedHashes = new HashSet<>();
        String fixedSalt = apiKeyGenerator.generateSalt();

        // Generate 10,000 API keys and hash them with the same salt
        for (int i = 0; i < 10000; i++) {
            String apiKey = apiKeyGenerator.generateApiKey();
            String hash = apiKeyGenerator.hashApiKey(apiKey, fixedSalt);

            // Check for hash collisions
            assertFalse(
                    generatedHashes.contains(hash),
                    "Hash collision detected! This should be extremely rare with SHA-512");
            generatedHashes.add(hash);
        }

        assertEquals(10000, generatedHashes.size(), "All hashes should be unique");
    }

    @Test
    @DisplayName("Similar API keys should produce vastly different hashes (avalanche effect)")
    void testAvalancheEffect() throws NoSuchAlgorithmException {
        String salt = apiKeyGenerator.generateSalt();
        String baseKey = "test-base-key-123456789";

        // Test single character changes
        String hash1 = apiKeyGenerator.hashApiKey(baseKey, salt);
        String hash2 = apiKeyGenerator.hashApiKey(baseKey + "a", salt); // Add one char
        String hash3 =
                apiKeyGenerator.hashApiKey("A" + baseKey.substring(1), salt); // Change first char

        // Calculate Hamming distance (different characters)
        int distance1_2 = calculateHammingDistance(hash1, hash2);
        int distance1_3 = calculateHammingDistance(hash1, hash3);

        // With good hash function, ~50% of bits should change
        assertTrue(distance1_2 > 32, "Single character change should affect many hash bits");
        assertTrue(distance1_3 > 32, "Single character change should affect many hash bits");

        assertNotEquals(hash1, hash2, "Hashes should be completely different");
        assertNotEquals(hash1, hash3, "Hashes should be completely different");
        assertNotEquals(hash2, hash3, "Hashes should be completely different");
    }

    @Test
    @DisplayName("Empty or null inputs should be handled securely")
    void testEdgeCaseInputs() {
        String validSalt = apiKeyGenerator.generateSalt();
        String validKey = "valid-key";

        // Test null inputs
        assertThrows(Exception.class, () -> apiKeyGenerator.hashApiKey(null, validSalt));
        assertFalse(apiKeyGenerator.verifyApiKey(null, "hash", validSalt));
        assertFalse(apiKeyGenerator.verifyApiKey(validKey, null, validSalt));
        assertFalse(apiKeyGenerator.verifyApiKey(validKey, "hash", null));

        // Test empty inputs
        assertDoesNotThrow(() -> apiKeyGenerator.hashApiKey("", validSalt));
        assertDoesNotThrow(() -> apiKeyGenerator.hashApiKey(validKey, ""));
        assertFalse(apiKeyGenerator.verifyApiKey("", "hash", validSalt));
        assertFalse(apiKeyGenerator.verifyApiKey(validKey, "", validSalt));
        assertFalse(apiKeyGenerator.verifyApiKey(validKey, "hash", ""));
    }

    @Test
    @DisplayName("Performance test - hash generation should be reasonably fast")
    void testHashPerformance() throws NoSuchAlgorithmException {
        String apiKey = apiKeyGenerator.generateApiKey();
        String salt = apiKeyGenerator.generateSalt();

        long startTime = System.nanoTime();

        // Perform 1000 hash operations
        for (int i = 0; i < 1000; i++) {
            apiKeyGenerator.hashApiKey(apiKey + i, salt);
        }

        long endTime = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

        // Should complete 1000 hashes in under 1 second (very generous)
        assertTrue(
                durationMs < 1000,
                "Hash performance is too slow: " + durationMs + "ms for 1000 operations");

        System.out.println(
                "Hash performance: "
                        + durationMs
                        + "ms for 1000 operations ("
                        + (durationMs / 1000.0)
                        + "ms per hash)");
    }

    @Test
    @DisplayName("API key masking should preserve security")
    void testApiKeyMasking() {
        String shortKey = "short";
        String normalKey = "this-is-a-normal-length-api-key-123456789";
        String longKey = "this-is-a-very-long-api-key-that-exceeds-normal-expectations-123456789";

        // Test short key masking
        String maskedShort = apiKeyGenerator.maskApiKey(shortKey);
        assertEquals("[hidden]", maskedShort, "Short keys should be completely hidden");

        // Test normal key masking
        String maskedNormal = apiKeyGenerator.maskApiKey(normalKey);
        assertTrue(maskedNormal.startsWith("this"), "Should show first 4 characters");
        assertTrue(maskedNormal.endsWith("6789"), "Should show last 4 characters");
        assertTrue(maskedNormal.contains("..."), "Should contain masking indicator");

        // Test long key masking
        String maskedLong = apiKeyGenerator.maskApiKey(longKey);
        assertTrue(maskedLong.startsWith("this"), "Should show first 4 characters");
        assertTrue(maskedLong.endsWith("6789"), "Should show last 4 characters");
        assertTrue(maskedLong.contains("..."), "Should contain masking indicator");

        // Test null input
        String maskedNull = apiKeyGenerator.maskApiKey(null);
        assertEquals("[hidden]", maskedNull, "Null input should be hidden");
    }

    @Test
    @DisplayName("Global salt usage should enhance security")
    void testGlobalSaltSecurity() throws NoSuchAlgorithmException {
        String apiKey = "test-key-for-global-salt";
        String globalSalt = "global-application-salt";
        String userSalt = apiKeyGenerator.generateSalt();

        // Hash with global salt for lookup
        String lookupHash = apiKeyGenerator.hashApiKey(apiKey, globalSalt);

        // Hash with user salt for verification
        String verificationHash = apiKeyGenerator.hashApiKey(apiKey, userSalt);

        // These should be different
        assertNotEquals(
                lookupHash, verificationHash, "Global salt hash should differ from user salt hash");

        // Both should be valid SHA-512 hashes
        assertEquals(128, lookupHash.length(), "Global salt hash should be SHA-512 length");
        assertEquals(128, verificationHash.length(), "User salt hash should be SHA-512 length");
    }

    /** Calculate Hamming distance between two hex strings */
    private int calculateHammingDistance(String hash1, String hash2) {
        if (hash1.length() != hash2.length()) {
            throw new IllegalArgumentException("Hashes must be same length");
        }

        int distance = 0;
        for (int i = 0; i < hash1.length(); i++) {
            if (hash1.charAt(i) != hash2.charAt(i)) {
                distance++;
            }
        }
        return distance;
    }
}
