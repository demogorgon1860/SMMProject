package com.smmpanel.service.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.smmpanel.service.UserService;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class RateLimitServiceTest {

    @Autowired
    private RateLimitService rateLimitService;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private UserService userService;

    @MockBean
    private ValueOperations<String, Object> valueOperations;

    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_ENDPOINT = "/api/orders/**";

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void testAdminUserBypassRateLimit() {
        // Setup
        User adminUser = new User();
        adminUser.setRole(UserRole.ADMIN);
        when(userService.findById(TEST_USER_ID)).thenReturn(adminUser);

        // Test
        boolean result = rateLimitService.isRequestAllowed(TEST_USER_ID, TEST_ENDPOINT);

        // Verify
        assertTrue(result);
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void testRateLimitExceeded() {
        // Setup
        User normalUser = new User();
        normalUser.setRole(UserRole.USER);
        when(userService.findById(TEST_USER_ID)).thenReturn(normalUser);
        when(valueOperations.get(anyString())).thenReturn(101); // Over limit

        // Test
        boolean result = rateLimitService.isRequestAllowed(TEST_USER_ID, TEST_ENDPOINT);

        // Verify
        assertFalse(result);
    }

    @Test
    void testProgressiveRateLimiting() {
        // Setup
        User normalUser = new User();
        normalUser.setRole(UserRole.USER);
        when(userService.findById(TEST_USER_ID)).thenReturn(normalUser);
        
        // Simulate multiple violations
        when(valueOperations.get(matches("progressive:.*"))).thenReturn(2); // 2 previous violations
        when(valueOperations.get(matches("ratelimit:.*"))).thenReturn(50); // Current requests

        // Test
        boolean result = rateLimitService.isRequestAllowed(TEST_USER_ID, TEST_ENDPOINT);

        // Verify
        assertTrue(result); // Should still allow as under adjusted limit
        verify(valueOperations).increment(matches("ratelimit:.*"));
    }

    @Test
    void testConcurrentRequests() throws InterruptedException {
        // Setup
        User normalUser = new User();
        normalUser.setRole(UserRole.USER);
        when(userService.findById(TEST_USER_ID)).thenReturn(normalUser);
        when(valueOperations.get(anyString())).thenReturn(0);

        // Test concurrent requests
        boolean[] results = new boolean[10];
        Thread[] threads = new Thread[10];

        for (int i = 0; i < 10; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = rateLimitService.isRequestAllowed(TEST_USER_ID, TEST_ENDPOINT);
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify
        assertTrue(IntStream.range(0, 10).allMatch(i -> results[i]));
        verify(valueOperations, times(10)).increment(matches("ratelimit:.*"));
    }

    @Test
    void testRateLimitReset() {
        // Test
        rateLimitService.resetRateLimits(TEST_USER_ID);

        // Verify
        verify(redisTemplate).keys(matches("progressive:" + TEST_USER_ID + ":*"));
        verify(redisTemplate).delete(anySet());
    }
}
