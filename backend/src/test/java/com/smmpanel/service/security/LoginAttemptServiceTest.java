package com.smmpanel.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.monitoring.SecurityMetricsService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class LoginAttemptServiceTest {

    @Autowired private LoginAttemptService loginAttemptService;

    @MockBean private UserRepository userRepository;

    @MockBean private RedisTemplate<String, String> redisTemplate;

    @MockBean private SecurityMetricsService securityMetricsService;

    @MockBean private SecurityAuditService securityAuditService;

    @MockBean private ValueOperations<String, String> valueOperations;

    private MockHttpServletRequest request;
    private final String TEST_USERNAME = "testuser";
    private final String TEST_IP = "192.168.1.1";

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setRemoteAddr(TEST_IP);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void whenLoginFails_shouldIncrementFailedAttempts() {
        // Given
        User user = new User();
        user.setUsername(TEST_USERNAME);
        when(userRepository.findByUsername(TEST_USERNAME)).thenReturn(Optional.of(user));

        // When
        loginAttemptService.loginFailed(TEST_USERNAME, TEST_IP);

        // Then
        verify(valueOperations).increment(anyString());
        verify(userRepository).save(any(User.class));
        verify(securityMetricsService).recordFailedLogin(TEST_USERNAME, TEST_IP);
        verify(securityAuditService)
                .logAuthenticationAttempt(eq(TEST_USERNAME), eq(TEST_IP), eq(false), anyString());
    }

    @Test
    void whenLoginSucceeds_shouldResetFailedAttempts() {
        // Given
        User user = new User();
        user.setUsername(TEST_USERNAME);
        user.setFailedAttempts(3);
        when(userRepository.findByUsername(TEST_USERNAME)).thenReturn(Optional.of(user));

        // When
        loginAttemptService.loginSucceeded(TEST_USERNAME, TEST_IP);

        // Then
        verify(redisTemplate).delete(anyString());
        verify(userRepository).save(argThat(u -> u.getFailedAttempts() == 0));
        verify(securityMetricsService).recordSuccessfulLogin(TEST_USERNAME, TEST_IP);
        verify(securityAuditService)
                .logAuthenticationAttempt(TEST_USERNAME, TEST_IP, true, "Successful login");
    }

    @Test
    void whenMaxAttemptsExceeded_shouldLockAccount() {
        // Given
        User user = new User();
        user.setUsername(TEST_USERNAME);
        user.setFailedAttempts(4);
        when(userRepository.findByUsername(TEST_USERNAME)).thenReturn(Optional.of(user));
        when(valueOperations.get(anyString())).thenReturn("5");

        // When
        loginAttemptService.loginFailed(TEST_USERNAME, TEST_IP);

        // Then
        verify(userRepository).save(argThat(u -> u.isAccountLocked()));
        verify(securityMetricsService).recordAccountLockout(TEST_USERNAME);
        verify(securityAuditService).logAccountLockout(eq(TEST_USERNAME), eq(TEST_IP), eq(5));
    }

    @Test
    void whenUnderCaptchaThreshold_shouldNotRequireCaptcha() {
        // Given
        when(valueOperations.get(anyString())).thenReturn("2");

        // When
        boolean captchaRequired = loginAttemptService.isCaptchaRequired(TEST_USERNAME);

        // Then
        assertFalse(captchaRequired);
        verify(securityMetricsService, never()).recordCaptchaRequired(anyString());
    }

    @Test
    void whenOverCaptchaThreshold_shouldRequireCaptcha() {
        // Given
        when(valueOperations.get(anyString())).thenReturn("3");

        // When
        boolean captchaRequired = loginAttemptService.isCaptchaRequired(TEST_USERNAME);

        // Then
        assertTrue(captchaRequired);
        verify(securityMetricsService).recordCaptchaRequired(TEST_IP);
    }

    @Test
    void whenUnlockingAccount_shouldResetAllCounters() {
        // Given
        User user = new User();
        user.setUsername(TEST_USERNAME);
        user.setAccountLocked(true);
        user.setFailedAttempts(5);
        when(userRepository.findByUsername(TEST_USERNAME)).thenReturn(Optional.of(user));

        // When
        loginAttemptService.unlockAccount(TEST_USERNAME, "admin");

        // Then
        verify(redisTemplate).delete(anyString());
        verify(userRepository)
                .save(argThat(u -> !u.isAccountLocked() && u.getFailedAttempts() == 0));
        verify(securityMetricsService).recordAccountUnlock(TEST_USERNAME, "admin");
        verify(securityAuditService)
                .logAccountUnlock(TEST_USERNAME, "admin", "Manual unlock by admin");
    }
}
