package com.smmpanel.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure-Mockito coverage for {@link WelcomeCreditService}. Centers on the at-most-once CAS (UPDATE
 * ... WHERE welcome_credit_granted_at IS NULL) and the no-op branches.
 */
@ExtendWith(MockitoExtension.class)
class WelcomeCreditServiceTest {

    private static final BigDecimal AMOUNT = new BigDecimal("5.00");

    @Mock private UserRepository userRepository;
    @Mock private BalanceService balanceService;
    @InjectMocks private WelcomeCreditService service;

    private User user(long id) {
        return User.builder().id(id).username("u" + id).balance(BigDecimal.ZERO).build();
    }

    private void enableFeature(BigDecimal amount) {
        ReflectionTestUtils.setField(service, "amount", amount);
    }

    @Test
    @DisplayName("grantIfEligible: feature disabled (zero amount) → no-op, no DB writes")
    void disabled_when_amount_zero() {
        enableFeature(BigDecimal.ZERO);

        boolean granted = service.grantIfEligible(user(1L));

        assertThat(granted).isFalse();
        verifyNoInteractions(userRepository, balanceService);
    }

    @Test
    @DisplayName("grantIfEligible: feature disabled (null amount) → no-op")
    void disabled_when_amount_null() {
        enableFeature(null);
        assertThat(service.grantIfEligible(user(1L))).isFalse();
        verifyNoInteractions(userRepository, balanceService);
    }

    @Test
    @DisplayName("grantIfEligible: null user → no-op")
    void null_user_noop() {
        enableFeature(AMOUNT);
        assertThat(service.grantIfEligible(null)).isFalse();
        verifyNoInteractions(userRepository, balanceService);
    }

    @Test
    @DisplayName("grantIfEligible: user without id → no-op")
    void user_without_id_noop() {
        enableFeature(AMOUNT);
        assertThat(service.grantIfEligible(User.builder().build())).isFalse();
        verifyNoInteractions(userRepository, balanceService);
    }

    @Test
    @DisplayName("grantIfEligible: CAS wins → credits balance with the configured amount")
    void cas_wins_credits_balance() {
        enableFeature(AMOUNT);
        User u = user(42L);
        when(userRepository.markWelcomeCreditGranted(eq(42L), any(LocalDateTime.class)))
                .thenReturn(1);

        boolean granted = service.grantIfEligible(u);

        assertThat(granted).isTrue();
        verify(balanceService, times(1))
                .addBalance(eq(u), eq(AMOUNT), eq(null), eq("Welcome credit"));
    }

    @Test
    @DisplayName("grantIfEligible: CAS loses (already granted) → no balance write")
    void cas_loses_no_balance() {
        enableFeature(AMOUNT);
        User u = user(42L);
        when(userRepository.markWelcomeCreditGranted(eq(42L), any(LocalDateTime.class)))
                .thenReturn(0);

        boolean granted = service.grantIfEligible(u);

        assertThat(granted).isFalse();
        verifyNoInteractions(balanceService);
    }

    /**
     * Realistic concurrency check — N parallel callers, only the one whose CAS returns 1 should
     * credit. Mock counts the addBalance invocations and asserts exactly one happens.
     */
    @Test
    @DisplayName("grantIfEligible: concurrent callers → exactly one credit (at-most-once)")
    void concurrent_at_most_once() throws Exception {
        enableFeature(AMOUNT);
        User u = user(42L);
        AtomicInteger casCallCount = new AtomicInteger();
        when(userRepository.markWelcomeCreditGranted(eq(42L), any(LocalDateTime.class)))
                .thenAnswer(inv -> casCallCount.incrementAndGet() == 1 ? 1 : 0);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger granted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                if (service.grantIfEligible(u)) granted.incrementAndGet();
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(granted.get()).isEqualTo(1);
        verify(balanceService, times(1))
                .addBalance(eq(u), eq(AMOUNT), eq(null), eq("Welcome credit"));
    }

    @Test
    @DisplayName("grantIfEligible: balance failure surfaces (caller decides whether to swallow)")
    void balance_failure_propagates() {
        enableFeature(AMOUNT);
        User u = user(42L);
        when(userRepository.markWelcomeCreditGranted(eq(42L), any(LocalDateTime.class)))
                .thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("balance hiccup"))
                .when(balanceService)
                .addBalance(any(User.class), any(BigDecimal.class), eq(null), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.grantIfEligible(u))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("balance hiccup");
        verify(userRepository, times(1)).markWelcomeCreditGranted(anyLong(), any());
        // We DO NOT clear the CAS row here — Spring's @Transactional rollback handles that
        // at the framework level. The service must not swallow the exception.
    }

    @Test
    @DisplayName("grantIfEligible: negative configured amount disables feature (defensive)")
    void negative_amount_disables() {
        enableFeature(new BigDecimal("-1"));
        assertThat(service.grantIfEligible(user(1L))).isFalse();
        verify(userRepository, never()).markWelcomeCreditGranted(anyLong(), any());
        verifyNoInteractions(balanceService);
    }
}
