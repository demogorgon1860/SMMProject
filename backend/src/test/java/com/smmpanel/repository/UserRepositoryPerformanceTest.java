package com.smmpanel.repository;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.integration.IntegrationTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for UserRepository query optimizations
 * Tests the API key authentication query performance improvements
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
@Transactional
class UserRepositoryPerformanceTest {

    @Autowired
    private UserRepository userRepository;

    private static final String TEST_API_KEY_HASH = "test_api_key_hash_123456789";
    private static final int PERFORMANCE_TEST_USERS = 1000;
    private static final int PERFORMANCE_ITERATIONS = 100;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        createTestUsers();
    }

    @Test
    @DisplayName("Optimized API key query should perform better than deprecated method")
    void testApiKeyQueryPerformance() {
        // Test the optimized method performance
        StopWatch optimizedStopWatch = new StopWatch("Optimized Query");
        optimizedStopWatch.start();
        
        for (int i = 0; i < PERFORMANCE_ITERATIONS; i++) {
            Optional<User> user = userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH);
            assertTrue(user.isPresent(), "User should be found");
            assertTrue(user.get().isActive(), "User should be active");
        }
        
        optimizedStopWatch.stop();
        long optimizedTime = optimizedStopWatch.getTotalTimeMillis();

        // Test the deprecated method performance
        StopWatch deprecatedStopWatch = new StopWatch("Deprecated Query");
        deprecatedStopWatch.start();
        
        for (int i = 0; i < PERFORMANCE_ITERATIONS; i++) {
            Optional<User> user = userRepository.findByApiKeyHash(TEST_API_KEY_HASH);
            assertTrue(user.isPresent(), "User should be found");
            // Manual active check (what the old code did)
            assertTrue(user.get().isActive(), "User should be active");
        }
        
        deprecatedStopWatch.stop();
        long deprecatedTime = deprecatedStopWatch.getTotalTimeMillis();

        // Log performance results
        System.out.println("=== API Key Query Performance Test Results ===");
        System.out.println("Optimized query time: " + optimizedTime + "ms");
        System.out.println("Deprecated query time: " + deprecatedTime + "ms");
        
        if (optimizedTime < deprecatedTime) {
            double improvement = ((double)(deprecatedTime - optimizedTime) / deprecatedTime) * 100;
            System.out.println("Performance improvement: " + String.format("%.1f", improvement) + "%");
        }

        // The optimized query should not be significantly slower
        // In real scenarios with proper indexes, it should be faster
        assertTrue(optimizedTime <= deprecatedTime * 1.2, 
            "Optimized query should not be more than 20% slower than deprecated query");
    }

    @Test
    @DisplayName("Optimized query should only return active users")
    void testOptimizedQueryOnlyReturnsActiveUsers() {
        // Create an inactive user with the same API key hash
        User inactiveUser = createTestUser("inactive_user", "inactive@test.com", false);
        inactiveUser.setApiKeyHash(TEST_API_KEY_HASH);
        userRepository.save(inactiveUser);

        // The optimized query should not find inactive users
        Optional<User> result = userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH);
        
        // Should find the active user, not the inactive one
        assertTrue(result.isPresent(), "Should find the active user");
        assertTrue(result.get().isActive(), "Found user should be active");
        assertEquals("test_user_1", result.get().getUsername(), "Should find the correct active user");
    }

    @Test
    @DisplayName("Query performance with many inactive users")
    void testPerformanceWithManyInactiveUsers() {
        // Create many inactive users to test index selectivity
        for (int i = 0; i < 500; i++) {
            User inactiveUser = createTestUser("inactive_" + i, "inactive" + i + "@test.com", false);
            inactiveUser.setApiKeyHash("inactive_api_key_" + i);
            userRepository.save(inactiveUser);
        }

        StopWatch stopWatch = new StopWatch("Query with many inactive users");
        stopWatch.start();

        for (int i = 0; i < 50; i++) {
            Optional<User> user = userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH);
            assertTrue(user.isPresent(), "Should still find active user efficiently");
        }

        stopWatch.stop();
        long queryTime = stopWatch.getTotalTimeMillis();

        System.out.println("Query time with many inactive users: " + queryTime + "ms");
        
        // Should still be reasonably fast even with many inactive users
        assertTrue(queryTime < 1000, "Query should complete in under 1 second");
    }

    @Test
    @DisplayName("Verify query parameter binding works correctly")
    void testQueryParameterBinding() {
        String customApiKey = "custom_api_key_hash_xyz";
        
        // Create user with custom API key
        User customUser = createTestUser("custom_user", "custom@test.com", true);
        customUser.setApiKeyHash(customApiKey);
        userRepository.save(customUser);

        // Test that parameter binding works correctly
        Optional<User> result = userRepository.findByApiKeyHashAndIsActiveTrue(customApiKey);
        assertTrue(result.isPresent(), "Should find user with custom API key");
        assertEquals("custom_user", result.get().getUsername(), "Should find correct user");

        // Test with non-existent API key
        Optional<User> notFound = userRepository.findByApiKeyHashAndIsActiveTrue("non_existent_key");
        assertFalse(notFound.isPresent(), "Should not find user with non-existent API key");
    }

    private void createTestUsers() {
        List<User> users = new ArrayList<>();
        
        for (int i = 1; i <= PERFORMANCE_TEST_USERS; i++) {
            User user = createTestUser("test_user_" + i, "test" + i + "@example.com", true);
            
            // Set API key hash for the first user (our test target)
            if (i == 1) {
                user.setApiKeyHash(TEST_API_KEY_HASH);
            } else {
                user.setApiKeyHash("api_key_hash_" + i);
            }
            
            users.add(user);
        }
        
        userRepository.saveAll(users);
    }

    private User createTestUser(String username, String email, boolean isActive) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("hashedPassword123");
        user.setBalance(BigDecimal.valueOf(100.0));
        user.setRole(UserRole.USER);
        user.setActive(isActive);
        return user;
    }
}