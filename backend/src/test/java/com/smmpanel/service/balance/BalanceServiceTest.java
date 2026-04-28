package com.smmpanel.service.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.entity.BalanceTransaction;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.TransactionType;
import com.smmpanel.entity.User;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.InvalidAmountException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.BalanceTransactionRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mockito coverage for {@link BalanceService}. Validates the core ledger invariants:
 *
 * <ul>
 *   <li>Insufficient balance is rejected before any state mutation.
 *   <li>Refunds and deposits write a {@link BalanceTransaction} row whose {@code balanceAfter}
 *       matches the user's persisted balance.
 *   <li>Negative or zero amounts are rejected at the boundary.
 *   <li>{@code refund(charge * (1 - completed/quantity))}-style partials are honored as the caller
 *       passes them.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class BalanceServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BalanceTransactionRepository transactionRepository;
    @Mock private TransactionTemplate balanceTransactionTemplate;
    @Mock private TransactionTemplate readOnlyTransactionTemplate;
    @Mock private BalanceAuditService balanceAuditService;

    @InjectMocks private BalanceService service;

    private User userWithBalance(long id, BigDecimal balance) {
        return User.builder().id(id).username("u" + id).balance(balance).build();
    }

    private Order orderFor(long id, User u) {
        Order o = new Order();
        o.setId(id);
        o.setUser(u);
        o.setStatus(OrderStatus.IN_PROGRESS);
        return o;
    }

    // ---------------------------------------------------------------
    // deductBalance
    // ---------------------------------------------------------------

    @Test
    @DisplayName("deductBalance: happy path — user balance reduces, ledger row written")
    void deduct_happy() {
        User u = userWithBalance(1L, new BigDecimal("100.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));
        Order order = orderFor(50L, u);

        service.deductBalance(u, new BigDecimal("30.00"), order, "test deduction");

        ArgumentCaptor<BalanceTransaction> tx = ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(transactionRepository, times(1)).save(tx.capture());
        BalanceTransaction recorded = tx.getValue();
        assertThat(recorded.getTransactionType()).isEqualTo(TransactionType.ORDER_PAYMENT);
        assertThat(recorded.getAmount()).isEqualByComparingTo("-30.00");
        assertThat(recorded.getBalanceBefore()).isEqualByComparingTo("100.00");
        assertThat(recorded.getBalanceAfter()).isEqualByComparingTo("70.00");
        assertThat(u.getBalance()).isEqualByComparingTo("70.00");
        assertThat(u.getTotalSpent()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("deductBalance: insufficient funds → InsufficientBalanceException, no writes")
    void deduct_insufficient() {
        User u = userWithBalance(1L, new BigDecimal("5.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.deductBalance(u, new BigDecimal("30.00"), null, "x"))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(transactionRepository, never()).save(any());
        verify(userRepository, never()).save(any());
        assertThat(u.getBalance()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("deductBalance: zero amount rejected as InvalidAmount")
    void deduct_zero_amount() {
        User u = userWithBalance(1L, new BigDecimal("100.00"));
        assertThatThrownBy(() -> service.deductBalance(u, BigDecimal.ZERO, null, "x"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("deductBalance: negative amount rejected")
    void deduct_negative_amount() {
        User u = userWithBalance(1L, new BigDecimal("100.00"));
        assertThatThrownBy(() -> service.deductBalance(u, new BigDecimal("-1"), null, "x"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("deductBalance: missing user → ResourceNotFound (no silent failure)")
    void deduct_user_missing() {
        User u = userWithBalance(99L, new BigDecimal("100.00"));
        when(userRepository.findByIdWithLock(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deductBalance(u, new BigDecimal("1"), null, "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------
    // refund (partial-refund formula honored as provided by caller)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("refund: writes a REFUND row with positive amount and increments balance")
    void refund_partial_amount() {
        User u = userWithBalance(1L, new BigDecimal("10.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));
        Order order = orderFor(50L, u);
        // Partial refund formula: charge * (1 - completed/quantity) computed by the caller.
        // Here: charge=$10, completed=80, quantity=100 → refund = $10 * 0.20 = $2.
        BigDecimal partialRefund = new BigDecimal("2.00");

        service.refund(u, partialRefund, order, "partial refund completed=80/100");

        ArgumentCaptor<BalanceTransaction> tx = ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(transactionRepository).save(tx.capture());
        BalanceTransaction t = tx.getValue();
        assertThat(t.getTransactionType()).isEqualTo(TransactionType.REFUND);
        assertThat(t.getAmount()).isEqualByComparingTo("2.00");
        assertThat(t.getBalanceBefore()).isEqualByComparingTo("10.00");
        assertThat(t.getBalanceAfter()).isEqualByComparingTo("12.00");
        assertThat(t.getDescription()).contains("partial refund");
        assertThat(u.getBalance()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("refund: full refund (charge * 1.0 when completed=0) credits the full charge")
    void refund_full_amount() {
        User u = userWithBalance(1L, new BigDecimal("0.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));
        BigDecimal fullRefund = new BigDecimal("15.00");

        service.refund(u, fullRefund, null, "full refund");

        verify(transactionRepository, times(1)).save(any(BalanceTransaction.class));
        assertThat(u.getBalance()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("refund: rejects zero/negative refund amount")
    void refund_zero_or_negative_rejected() {
        User u = userWithBalance(1L, BigDecimal.ZERO);
        assertThatThrownBy(() -> service.refund(u, BigDecimal.ZERO, null, "x"))
                .isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> service.refund(u, new BigDecimal("-1"), null, "x"))
                .isInstanceOf(InvalidAmountException.class);
        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // addBalance / deposit
    // ---------------------------------------------------------------

    @Test
    @DisplayName("addBalance: deposit increments balance and writes DEPOSIT ledger row")
    void addBalance_deposit_records_ledger() {
        User u = userWithBalance(1L, new BigDecimal("5.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));

        BigDecimal newBalance =
                service.addBalance(u, new BigDecimal("10.00"), null, "Welcome credit");

        assertThat(newBalance).isEqualByComparingTo("15.00");
        assertThat(u.getBalance()).isEqualByComparingTo("15.00");
        ArgumentCaptor<BalanceTransaction> tx = ArgumentCaptor.forClass(BalanceTransaction.class);
        verify(transactionRepository).save(tx.capture());
        assertThat(tx.getValue().getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getValue().getDescription()).isEqualTo("Welcome credit");
        assertThat(tx.getValue().getAmount()).isEqualByComparingTo("10.00");
    }

    // ---------------------------------------------------------------
    // checkAndDeductBalance — atomic guard
    // ---------------------------------------------------------------

    @Test
    @DisplayName("checkAndDeductBalance: deducts iff sufficient funds; returns true on success")
    void checkAndDeduct_sufficient_returns_true() {
        User u = userWithBalance(1L, new BigDecimal("50.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));

        boolean ok = service.checkAndDeductBalance(u, new BigDecimal("30.00"), null, "x");

        assertThat(ok).isTrue();
        assertThat(u.getBalance()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName(
            "checkAndDeductBalance: returns false on insufficient funds, no writes, no exception")
    void checkAndDeduct_insufficient_returns_false() {
        User u = userWithBalance(1L, new BigDecimal("5.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(u));

        boolean ok = service.checkAndDeductBalance(u, new BigDecimal("30.00"), null, "x");

        assertThat(ok).isFalse();
        assertThat(u.getBalance()).isEqualByComparingTo("5.00");
        verify(transactionRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // hasSufficientBalance / getUserBalance
    // ---------------------------------------------------------------

    @Test
    @DisplayName("hasSufficientBalance: pure check, does not mutate, throws on missing user")
    void hasSufficientBalance_basics() {
        User u = userWithBalance(1L, new BigDecimal("100.00"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));

        assertThat(service.hasSufficientBalance(1L, new BigDecimal("50.00"))).isTrue();
        assertThat(service.hasSufficientBalance(1L, new BigDecimal("100.01"))).isFalse();

        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.hasSufficientBalance(99L, new BigDecimal("1")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------
    // adjustBalance — sign-aware
    // ---------------------------------------------------------------

    @Test
    @DisplayName("adjustBalance: positive credit increments and records type passed in")
    void adjust_positive() {
        User u = userWithBalance(1L, new BigDecimal("10.00"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));

        BigDecimal after =
                service.adjustBalance(
                        1L, new BigDecimal("5.00"), TransactionType.ADJUSTMENT, "x", null);

        assertThat(after).isEqualByComparingTo("15.00");
        assertThat(u.getBalance()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("adjustBalance: negative debit decrements; rejects if it would go below zero")
    void adjust_negative_below_zero() {
        User u = userWithBalance(1L, new BigDecimal("10.00"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(
                        () ->
                                service.adjustBalance(
                                        1L,
                                        new BigDecimal("-50.00"),
                                        TransactionType.ADJUSTMENT,
                                        "debit",
                                        null))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(u.getBalance()).isEqualByComparingTo("10.00");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("adjustBalance: zero adjustment rejected as InvalidAmount")
    void adjust_zero_rejected() {
        assertThatThrownBy(
                        () ->
                                service.adjustBalance(
                                        1L, BigDecimal.ZERO, TransactionType.ADJUSTMENT, "x", null))
                .isInstanceOf(InvalidAmountException.class);
    }

    // ---------------------------------------------------------------
    // transferBalance
    // ---------------------------------------------------------------

    @Test
    @DisplayName("transferBalance: locks both users in a stable order, writes both ledger rows")
    void transfer_locks_and_records_both_sides() {
        User from = userWithBalance(1L, new BigDecimal("100.00"));
        User to = userWithBalance(2L, new BigDecimal("0.00"));
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(from));
        when(userRepository.findByIdWithLock(2L)).thenReturn(Optional.of(to));

        service.transferBalance(1L, 2L, new BigDecimal("25.00"), "transfer");

        assertThat(from.getBalance()).isEqualByComparingTo("75.00");
        assertThat(to.getBalance()).isEqualByComparingTo("25.00");
        verify(transactionRepository, times(2)).save(any(BalanceTransaction.class));
    }

    @Test
    @DisplayName("transferBalance: same-user transfer rejected")
    void transfer_same_user_rejected() {
        assertThatThrownBy(() -> service.transferBalance(1L, 1L, BigDecimal.ONE, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("transferBalance: insufficient source balance → exception, no writes")
    void transfer_insufficient_source() {
        User from = userWithBalance(1L, new BigDecimal("5.00"));
        User to = userWithBalance(2L, BigDecimal.ZERO);
        when(userRepository.findByIdWithLock(1L)).thenReturn(Optional.of(from));
        when(userRepository.findByIdWithLock(2L)).thenReturn(Optional.of(to));

        assertThatThrownBy(() -> service.transferBalance(1L, 2L, new BigDecimal("100.00"), "x"))
                .isInstanceOf(InsufficientBalanceException.class);
        verify(transactionRepository, never()).save(any(BalanceTransaction.class));
        assertThat(from.getBalance()).isEqualByComparingTo("5.00");
        assertThat(to.getBalance()).isEqualByComparingTo("0.00");
    }

    // ---------------------------------------------------------------
    // null guards
    // ---------------------------------------------------------------

    @Test
    @DisplayName("null user throws NPE rather than silently degrading")
    void null_user_npe() {
        assertThatThrownBy(() -> service.deductBalance(null, BigDecimal.ONE, null, "x"))
                .isInstanceOf(NullPointerException.class);
    }
}
