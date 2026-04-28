package com.smmpanel.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.entity.EmailVerificationToken;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.EmailVerificationTokenRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.EmailVerificationService.ResendResult;
import com.smmpanel.service.email.EmailService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Mockito coverage for {@link EmailVerificationService}. Exercises the OTP issue/redeem state
 * machine, the per-user resend cooldown, and the at-most-once redeem (concurrent verify guard).
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final long USER_ID = 1L;
    private static final String EMAIL = "ada@example.com";
    private static final String CODE = "123456";

    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private WelcomeCreditService welcomeCreditService;

    @InjectMocks private EmailVerificationService service;

    private User unverifiedUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ttlHours", 24);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60);
        unverifiedUser =
                User.builder()
                        .id(USER_ID)
                        .username("ada")
                        .email(EMAIL)
                        .emailVerified(false)
                        .build();
    }

    // ---------------------------------------------------------------
    // verify
    // ---------------------------------------------------------------

    @Test
    @DisplayName("verify: happy path — claims token, flips user, attempts welcome credit")
    void verify_happy_path() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        EmailVerificationToken token = activeToken(10L, EMAIL, CODE);
        when(tokenRepository.findActiveForUser(
                        eq(USER_ID), eq(EmailVerificationService.sha256(CODE))))
                .thenReturn(Optional.of(token));
        when(tokenRepository.markUsedIfUnused(eq(10L), any(LocalDateTime.class))).thenReturn(1);

        boolean ok = service.verify(EMAIL, CODE);

        assertThat(ok).isTrue();
        assertThat(unverifiedUser.isEmailVerified()).isTrue();
        assertThat(unverifiedUser.getEmailVerifiedAt()).isNotNull();
        verify(welcomeCreditService, times(1)).grantIfEligible(unverifiedUser);
        verify(emailService, times(1)).sendWelcome(EMAIL, "ada");
    }

    @Test
    @DisplayName("verify: nulls return false (no NPE, no DB calls)")
    void verify_null_inputs_safe() {
        assertThat(service.verify(null, CODE)).isFalse();
        assertThat(service.verify(EMAIL, null)).isFalse();
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("verify: unknown email returns false (no enumeration leak)")
    void verify_unknown_email() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        assertThat(service.verify(EMAIL, CODE)).isFalse();
        verify(tokenRepository, never()).findActiveForUser(anyLong(), anyString());
    }

    @Test
    @DisplayName("verify: already-verified user is short-circuited as success (idempotent)")
    void verify_already_verified_idempotent() {
        unverifiedUser.setEmailVerified(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));

        assertThat(service.verify(EMAIL, CODE)).isTrue();
        verify(tokenRepository, never()).findActiveForUser(anyLong(), anyString());
        verify(welcomeCreditService, never()).grantIfEligible(any());
    }

    @Test
    @DisplayName("verify: missing token (wrong code) returns false")
    void verify_wrong_code() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        when(tokenRepository.findActiveForUser(eq(USER_ID), anyString()))
                .thenReturn(Optional.empty());

        assertThat(service.verify(EMAIL, CODE)).isFalse();
        verify(tokenRepository, never()).markUsedIfUnused(anyLong(), any());
    }

    @Test
    @DisplayName("verify: expired token returns false even before the redeem")
    void verify_expired_token() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        EmailVerificationToken expired = activeToken(10L, EMAIL, CODE);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findActiveForUser(eq(USER_ID), anyString()))
                .thenReturn(Optional.of(expired));

        assertThat(service.verify(EMAIL, CODE)).isFalse();
        verify(tokenRepository, never()).markUsedIfUnused(anyLong(), any());
    }

    @Test
    @DisplayName("verify: concurrent redeem → only the CAS winner returns true")
    void verify_concurrent_redeem_only_one_wins() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        EmailVerificationToken token = activeToken(10L, EMAIL, CODE);
        when(tokenRepository.findActiveForUser(eq(USER_ID), anyString()))
                .thenReturn(Optional.of(token));
        // The second concurrent verify gets back 0 from markUsedIfUnused — token already claimed.
        when(tokenRepository.markUsedIfUnused(eq(10L), any(LocalDateTime.class))).thenReturn(0);

        assertThat(service.verify(EMAIL, CODE)).isFalse();
        assertThat(unverifiedUser.isEmailVerified()).isFalse();
        verify(welcomeCreditService, never()).grantIfEligible(any());
    }

    @Test
    @DisplayName("verify: welcome credit failure does not undo the verification")
    void verify_credit_failure_isolated() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        EmailVerificationToken token = activeToken(10L, EMAIL, CODE);
        when(tokenRepository.findActiveForUser(eq(USER_ID), anyString()))
                .thenReturn(Optional.of(token));
        when(tokenRepository.markUsedIfUnused(eq(10L), any(LocalDateTime.class))).thenReturn(1);
        when(welcomeCreditService.grantIfEligible(unverifiedUser))
                .thenThrow(new RuntimeException("balance svc down"));

        assertThat(service.verify(EMAIL, CODE)).isTrue();
        assertThat(unverifiedUser.isEmailVerified()).isTrue();
        verify(emailService, times(1)).sendWelcome(EMAIL, "ada");
    }

    @Test
    @DisplayName("verify: trims and lowercases email before lookup")
    void verify_email_trimmed_lowercased() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        when(tokenRepository.findActiveForUser(eq(USER_ID), anyString()))
                .thenReturn(Optional.empty());

        service.verify("  Ada@Example.com  ", CODE);

        verify(userRepository).findByEmail(EMAIL);
    }

    // ---------------------------------------------------------------
    // resend / cooldown
    // ---------------------------------------------------------------

    @Test
    @DisplayName("resend: unknown email returns OK (no enumeration leak)")
    void resend_unknown_email_silent() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ResendResult r = service.resend(EMAIL);

        assertThat(r.isOk()).isTrue();
        verify(emailService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    @DisplayName("resend: cooldown active → cooldown response + retryAfterSeconds > 0")
    void resend_cooldown_active() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        EmailVerificationToken latest = activeToken(10L, EMAIL, CODE);
        latest.setCreatedAt(LocalDateTime.now().minusSeconds(5)); // < 60s cooldown
        when(tokenRepository.findLatestActiveByUserId(USER_ID)).thenReturn(Optional.of(latest));

        ResendResult r = service.resend(EMAIL);

        assertThat(r.isOk()).isFalse();
        assertThat(r.getRetryAfterSeconds()).isGreaterThan(0);
        verify(emailService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    @DisplayName("resend: cooldown expired → issues a fresh code")
    void resend_cooldown_expired_issues() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        EmailVerificationToken latest = activeToken(10L, EMAIL, CODE);
        latest.setCreatedAt(LocalDateTime.now().minusSeconds(120));
        when(tokenRepository.findLatestActiveByUserId(USER_ID)).thenReturn(Optional.of(latest));

        ResendResult r = service.resend(EMAIL);

        assertThat(r.isOk()).isTrue();
        verify(tokenRepository, times(1)).save(any(EmailVerificationToken.class));
        verify(emailService, times(1)).sendVerificationCode(eq(EMAIL), eq("ada"), anyString());
    }

    @Test
    @DisplayName("resend: no prior token → issues a new code immediately (no cooldown)")
    void resend_no_prior_token() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));
        when(tokenRepository.findLatestActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        ResendResult r = service.resend(EMAIL);

        assertThat(r.isOk()).isTrue();
        verify(tokenRepository, times(1)).save(any(EmailVerificationToken.class));
    }

    @Test
    @DisplayName("resend: already-verified user returns OK without doing anything")
    void resend_already_verified_silent() {
        unverifiedUser.setEmailVerified(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(unverifiedUser));

        ResendResult r = service.resend(EMAIL);

        assertThat(r.isOk()).isTrue();
        verify(emailService, never()).sendVerificationCode(any(), any(), any());
    }

    // ---------------------------------------------------------------
    // issueCodeFor
    // ---------------------------------------------------------------

    @Test
    @DisplayName("issueCodeFor: invalidates prior tokens, saves a fresh hashed code, sends email")
    void issueCodeFor_invalidates_prior_then_saves() {
        service.issueCodeFor(unverifiedUser);

        verify(tokenRepository, times(1)).markAllUsedForUser(eq(USER_ID), any(LocalDateTime.class));
        verify(tokenRepository, times(1))
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                t ->
                                        t != null
                                                && t.getUserId().equals(USER_ID)
                                                && t.getCodeHash() != null
                                                && t.getCodeHash().length() == 64
                                                && t.getExpiresAt() != null
                                                && t.getExpiresAt().isAfter(LocalDateTime.now())));
        verify(emailService, times(1)).sendVerificationCode(eq(EMAIL), eq("ada"), anyString());
    }

    @Test
    @DisplayName("issueCodeFor: skips already-verified users")
    void issueCodeFor_skips_verified() {
        unverifiedUser.setEmailVerified(true);
        service.issueCodeFor(unverifiedUser);
        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendVerificationCode(any(), any(), any());
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private EmailVerificationToken activeToken(long id, String email, String code) {
        return EmailVerificationToken.builder()
                .id(id)
                .userId(USER_ID)
                .codeHash(EmailVerificationService.sha256(code))
                .expiresAt(LocalDateTime.now().plusHours(24))
                .createdAt(LocalDateTime.now())
                .build();
    }
}
