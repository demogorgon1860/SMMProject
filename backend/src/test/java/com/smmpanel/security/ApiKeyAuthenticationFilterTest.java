package com.smmpanel.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.ApiKeyService;
import com.smmpanel.service.security.AuthenticationRateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for the fast-path/legacy-fallback dual-mode authentication in {@link
 * ApiKeyAuthenticationFilter}. The fast path resolves the owning user via the indexed
 * {@code api_key_lookup_hash} column; the legacy path scans every active user whose lookup
 * hash is still null and lazily backfills it on first success.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    @Mock private UserRepository userRepository;
    @Mock private ApiKeyService apiKeyService;
    @Mock private AuthenticationRateLimitService rateLimitService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private ApiKeyAuthenticationFilter filter;

    private static final String TEST_API_KEY = "test-api-key-123";
    private static final String TEST_LOOKUP_HASH = "deterministic-lookup-hash-123";

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthenticationFilter(userRepository, apiKeyService, rateLimitService);
        ReflectionTestUtils.setField(filter, "apiKeyAuthEnabled", true);
        ReflectionTestUtils.setField(filter, "apiKeyHeader", "X-API-Key");
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Fast path: lookup-hash resolves user → single per-user-salt verify")
    void testFastPathSuccess() throws ServletException, IOException {
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_LOOKUP_HASH);
        when(userRepository.findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(
                        eq(TEST_API_KEY),
                        eq(testUser.getApiKeyHash()),
                        eq(testUser.getApiKeySalt()),
                        anyString()))
                .thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(userRepository, times(1)).findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH);
        // Fast path must NOT trigger the legacy O(N) scan.
        verify(userRepository, never()).findAllByIsActiveTrue();
        verify(apiKeyService, never())
                .backfillLookupHashIfMissing(any(), anyString()); // already populated
        verify(filterChain, times(1)).doFilter(request, response);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(
                testUser, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    @DisplayName("Legacy fallback: no lookup-hash row → scan + backfill on success")
    void testLegacyFallbackBackfills() throws ServletException, IOException {
        User legacyUser = createTestUser();
        legacyUser.setApiKeyLookupHash(null); // pre-migration row

        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_LOOKUP_HASH);
        when(userRepository.findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH))
                .thenReturn(Optional.empty());
        when(userRepository.findAllByIsActiveTrue()).thenReturn(List.of(legacyUser));
        when(apiKeyService.verifyApiKeyOnly(
                        eq(TEST_API_KEY),
                        eq(legacyUser.getApiKeyHash()),
                        eq(legacyUser.getApiKeySalt()),
                        anyString()))
                .thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(apiKeyService, times(1))
                .backfillLookupHashIfMissing(legacyUser.getId(), TEST_LOOKUP_HASH);
        verify(filterChain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Lookup hit but per-user verify fails → reject (defence in depth)")
    void testFastPathRequiresPerUserVerify() throws ServletException, IOException {
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_LOOKUP_HASH);
        when(userRepository.findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(
                        eq(TEST_API_KEY),
                        eq(testUser.getApiKeyHash()),
                        eq(testUser.getApiKeySalt()),
                        anyString()))
                .thenReturn(false);
        // Legacy fallback runs, but the only active user already has lookup_hash populated,
        // so it's filtered out — no candidates → final 401. The outer catch in
        // doFilterInternal turns the SecurityException into a generic "Authentication failed".
        when(userRepository.findAllByIsActiveTrue()).thenReturn(List.of(testUser));

        filter.doFilterInternal(request, response, filterChain);

        verify(response, times(1))
                .sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Invalid API key — neither fast path nor legacy match → 401")
    void testInvalidApiKey() throws ServletException, IOException {
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_LOOKUP_HASH);
        when(userRepository.findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH))
                .thenReturn(Optional.empty());
        when(userRepository.findAllByIsActiveTrue()).thenReturn(List.of()); // no legacy users

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

        // The filter throws SecurityException("Invalid API key") which the outer catch in
        // doFilterInternal maps to a generic 401 + "Authentication failed" body — same shape
        // as for any other auth-time failure, so attackers can't distinguish "wrong key" from
        // "DB blip".
        verify(response, times(1))
                .sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Database error during lookup → 401 (fail-closed)")
    void testDatabaseConnectionError() throws ServletException, IOException {
        when(request.getHeader("X-API-Key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_LOOKUP_HASH);
        when(userRepository.findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH))
                .thenThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "Database connection failed"));

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));

        verify(response, times(1))
                .sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication failed");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Perfect Panel: ?key= URL parameter falls through to filter")
    void testPerfectPanelCompatibility() throws ServletException, IOException {
        User testUser = createTestUser();
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("key")).thenReturn(TEST_API_KEY);
        when(apiKeyService.hashApiKeyForLookup(TEST_API_KEY)).thenReturn(TEST_LOOKUP_HASH);
        when(userRepository.findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH))
                .thenReturn(Optional.of(testUser));
        when(apiKeyService.verifyApiKeyOnly(
                        eq(TEST_API_KEY),
                        eq(testUser.getApiKeyHash()),
                        eq(testUser.getApiKeySalt()),
                        anyString()))
                .thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        verify(userRepository, times(1)).findByApiKeyLookupHashAndIsActiveTrue(TEST_LOOKUP_HASH);
        verify(filterChain, times(1)).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Already authenticated → skip API key auth entirely")
    void testSkipWhenAlreadyAuthenticated() throws ServletException, IOException {
        SecurityContextHolder.getContext()
                .setAuthentication(mock(org.springframework.security.core.Authentication.class));

        filter.doFilterInternal(request, response, filterChain);

        verify(request, never()).getHeader(anyString());
        verify(apiKeyService, never()).hashApiKeyForLookup(anyString());
        verify(userRepository, never()).findByApiKeyLookupHashAndIsActiveTrue(anyString());
        verify(filterChain, times(1)).doFilter(request, response);
    }

    private User createTestUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setPasswordHash("hashedPassword");
        user.setApiKeyHash("stored-hash-123");
        user.setApiKeySalt("salt-123");
        user.setApiKeyLookupHash(TEST_LOOKUP_HASH);
        user.setRole(UserRole.USER);
        user.setActive(true);
        user.setBalance(BigDecimal.valueOf(100.0));
        return user;
    }
}
