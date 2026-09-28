package com.smmpanel.service.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.hibernate.jpa.HibernateHints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    @Mock private EntityManager entityManager;
    @Mock private Query lockQuery;

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
    // Lock-acquisition stubs. BalanceService takes the balance row lock with a SCALAR native query
    // ("SELECT id ... FOR NO KEY UPDATE") that materializes no entity — any entity-returning lock
    // makes Hibernate version-check an already-managed User and reject the order that waited for
    // the lock (User#856; see lockAndRefreshUser) — then em.find + a plain em.refresh under it.
    // The real-database behavior is covered by UserBalanceLockIntegrationTest.
    // ---------------------------------------------------------------

    /** The row-lock query finds (and locks) each user's row; em.find resolves them by id. */
    private void stubLockedUsers(User... users) {
        stubLockQuery(List.of(1L));
        for (User u : users) {
            when(entityManager.find(User.class, u.getId())).thenReturn(u);
        }
    }

    /** The row-lock query finds no row (user missing). */
    private void stubUserMissing() {
        stubLockQuery(List.of());
    }

    private void stubLockQuery(List<?> lockedRows) {
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq("id"), any())).thenReturn(lockQuery);
        when(lockQuery.setHint(anyString(), any())).thenReturn(lockQuery);
        when(lockQuery.getResultList()).thenReturn(lockedRows);
    }

    // ---------------------------------------------------------------
    // deductBalance
    // ---------------------------------------------------------------

    @Test
    @DisplayName("deductBalance: happy path — user balance reduces, ledger row written")
    void deduct_happy() {
        User u = userWithBalance(1L, new BigDecimal("100.00"));
        stubLockedUsers(u);
        Order order = orderFor(50L, u);

        service.deductBalance(u, new BigDecimal("30.00"), order, "test deduction");

        // Regression guards for the User#856 incident: the row lock MUST be a scalar statement
        // (nothing materialized, so Hibernate has no managed User to version-check), flushing the
        // same User space the old JPQL lock did, and the User MUST be refreshed only AFTER the
        // lock so its @Version/balance reflect the lock-winner's committed row.
        ArgumentCaptor<String> lockSql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(lockSql.capture());
        assertThat(lockSql.getValue())
                .isEqualTo("SELECT id FROM users WHERE id = :id FOR NO KEY UPDATE");
        verify(lockQuery).setParameter("id", 1L);
        verify(lockQuery).setHint(HibernateHints.HINT_NATIVE_SPACES, User.class);
        InOrder lockThenReload = inOrder(lockQuery, entityManager);
        lockThenReload.verify(lockQuery).getResultList();
        lockThenReload.verify(entityManager).refresh(u);
        // ...and no entity-returning or version-checked lock anywhere.
        verify(entityManager, never()).createQuery(anyString(), eq(User.class));
        verify(entityManager, never()).lock(any(), any(LockModeType.class));
        verify(entityManager, never()).find(eq(User.class), any(), any(LockModeType.class));
        verify(entityManager, never()).refresh(any(), any(LockModeType.class));

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
        stubLockedUsers(u);

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
        stubUserMissing();

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
        stubLockedUsers(u);
        Order order = orderFor(50L, u);
        // Partial refund formula: charge * (1 - completed/quantity) computed by the caller.
        // Here: charge=$10, completed=80, quantity=100 → refund = $10 * 0.20 = $2.
        BigDecimal partialRefund = new BigDecimal("2.00");

        service.refund(u, partialRefund, order, "partial refund completed=80/100");

        // Regression guard: refund also locks + refreshes the User (commit cff8a95f).
        verify(entityManager).refresh(u);

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
        stubLockedUsers(u);
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
        stubLockedUsers(u);

        BigDecimal newBalance =
                service.addBalance(u, new BigDecimal("10.00"), null, "Welcome credit");

        assertThat(newBalance).isEqualByComparingTo("15.00");
        assertThat(u.getBalance()).isEqualByComparingTo("15.00");
        // Regression guard: addBalance also locks + refreshes the User (commit cff8a95f).
        verify(entityManager).refresh(u);
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
        stubLockedUsers(u);

        boolean ok = service.checkAndDeductBalance(u, new BigDecimal("30.00"), null, "x");

        assertThat(ok).isTrue();
        assertThat(u.getBalance()).isEqualByComparingTo("20.00");
        // Regression guard: the atomic guard also locks + refreshes the User (commit cff8a95f).
        verify(entityManager).refresh(u);
    }

    @Test
    @DisplayName(
            "checkAndDeductBalance: returns false on insufficient funds, no writes, no exception")
    void checkAndDeduct_insufficient_returns_false() {
        User u = userWithBalance(1L, new BigDecimal("5.00"));
        stubLockedUsers(u);

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
        // Transfer from the HIGHER id to the lower one: the locks must still be taken in
        // ascending-id order, so two opposite transfers between the same pair can't deadlock.
        User to = userWithBalance(1L, new BigDecimal("0.00"));
        User from = userWithBalance(2L, new BigDecimal("100.00"));
        stubLockedUsers(to, from);

        service.transferBalance(2L, 1L, new BigDecimal("25.00"), "transfer");

        InOrder ascendingIds = inOrder(entityManager);
        ascendingIds.verify(entityManager).find(User.class, 1L);
        ascendingIds.verify(entityManager).find(User.class, 2L);
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
        stubLockedUsers(from, to);

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
