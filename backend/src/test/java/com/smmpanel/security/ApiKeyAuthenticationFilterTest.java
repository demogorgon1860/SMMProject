package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.ApiKeyService;
import com.smmpanel.service.security.AuthenticationRateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test class for ApiKeyAuthenticationFilter performance optimizations
 * Verifies that the filter uses optimized database queries and performance logging
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private AuthenticationRateLimitService rateLimitService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private ApiKeyAuthenticationFilter filter;

    private static final String TEST_API_KEY = "test-api-key-123";
    private static final String TEST_API_KEY_HASH = "hashed-api-key-123";

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(userRepository, apiKeyService, rateLimitService);
        ReflectionTestUtils.setField(filter, "apiKeyAuthEnabled", true);
        ReflectionTestUtils.setField(filter, "apiKeyHeader", "X-API-Key");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should use optimized repository method for API key lookup")
    void testOptimizedRepositoryQuery() throws ServletException, IOException {
        // Arrange
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(request.getParameter("key")).thenReturn(null);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_API_KEY_HASH);
        when(userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(TEST_API_KEY, testUser.getApiKeyHash(), testUser.getApiKeySalt()))
                .thenReturn(true);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(userRepository, times(1)).findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH);
        verify(userRepository, never()).findByApiKeyHash(anyString()); // Should not use deprecated method
        verify(apiKeyService, times(1)).verifyApiKeyOnly(anyString(), anyString(), anyString());
        verify(apiKeyService, never()).validateApiKey(anyString(), any(User.class)); // Should not use method with DB write
        verify(filterChain, times(1)).doFilter(request, response);
        
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(testUser, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    @DisplayName("Should handle database connection errors gracefully")
    void testDatabaseConnectionError() throws ServletException, IOException {
        // Arrange
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_API_KEY_HASH);
        when(userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Database connection failed"));

        // Act & Assert
        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));
        
        verify(response, times(1)).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should not perform database writes during authentication")
    void testNoDatabaseWritesDuringAuth() throws ServletException, IOException {
        // Arrange
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_API_KEY_HASH);
        when(userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(TEST_API_KEY, testUser.getApiKeyHash(), testUser.getApiKeySalt()))
                .thenReturn(true);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(userRepository, times(1)).findByApiKeyHashAndIsActiveTrue(anyString()); // Only read operation
        verify(userRepository, never()).save(any()); // No write operations
        verify(userRepository, never()).saveAndFlush(any()); // No write operations
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("Should use fallback parameter for Perfect Panel compatibility")
    void testPerfectPanelCompatibility() throws ServletException, IOException {
        // Arrange
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(null); // No header
        when(request.getParameter("key")).thenReturn(TEST_API_KEY); // Fallback to parameter
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_API_KEY_HASH);
        when(userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(TEST_API_KEY, testUser.getApiKeyHash(), testUser.getApiKeySalt()))
                .thenReturn(true);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(userRepository, times(1)).findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH);
        verify(filterChain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should handle invalid API key without authentication")
    void testInvalidApiKey() throws ServletException, IOException {
        // Arrange
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_API_KEY_HASH);
        when(userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH))
                .thenReturn(Optional.empty()); // No user found

        // Act
        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

        // Assert
        verify(response, times(1)).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should skip API key auth when already authenticated")
    void testSkipWhenAlreadyAuthenticated() throws ServletException, IOException {
        // Arrange
        User testUser = createTestUser();
        SecurityContextHolder.getContext().setAuthentication(
                mock(org.springframework.security.core.Authentication.class));
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert
        verify(userRepository, never()).findByApiKeyHashAndIsActiveTrue(anyString());
        verify(apiKeyService, never()).hashApiKeyForLookup(anyString());
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("Should measure and log authentication performance")
    void testPerformanceLogging() throws ServletException, IOException {
        // Arrange
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_API_KEY_HASH);
        when(userRepository.findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(TEST_API_KEY, testUser.getApiKeyHash(), testUser.getApiKeySalt()))
                .thenReturn(true);

        // Act
        filter.doFilterInternal(request, response, filterChain);

        // Assert - The method should complete without errors and timing should be measured
        verify(userRepository, times(1)).findByApiKeyHashAndIsActiveTrue(TEST_API_KEY_HASH);
        verify(apiKeyService, times(1)).hashApiKeyForLookup(TEST_API_KEY);
        verify(apiKeyService, times(1)).verifyApiKeyOnly(anyString(), anyString(), anyString());
        verify(filterChain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private User createTestUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPasswordHash("hashedPassword");
        user.setApiKeyHash("stored-hash-123");
        user.setApiKeySalt("salt-123");
        user.setRole(UserRole.USER);
        user.setActive(true);
        user.setBalance(BigDecimal.valueOf(100.0));
        return user;
    }
}