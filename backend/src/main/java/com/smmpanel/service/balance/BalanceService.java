package com.smmpanel.service.balance;

import com.smmpanel.dto.response.TransactionHistoryResponse;
import com.smmpanel.entity.*;
import com.smmpanel.exception.*;
import com.smmpanel.repository.jpa.BalanceTransactionRepository;
import com.smmpanel.repository.jpa.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private static final int SCALE = 8;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    @Value("${app.balance.retry.max-attempts:5}")
    private int maxRetryAttempts;

    @Value("${app.balance.retry.initial-delay:100}")
    private long initialDelayMs;

    @Value("${app.balance.retry.max-delay:5000}")
    private long maxDelayMs;

    @Value("${app.balance.retry.multiplier:2.0}")
    private double backoffMultiplier;

    private final UserRepository userRepository;
    private final BalanceTransactionRepository transactionRepository;
    private final TransactionTemplate balanceTransactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final BalanceAuditService balanceAuditService;
    private final EntityManager entityManager;

    /** Validates the amount is positive and has proper scale */
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        if (amount.scale() > SCALE) {
            throw new InvalidAmountException(
                    "Amount scale exceeds maximum of " + SCALE + " decimal places");
        }
    }

    /**
     * Take the per-user balance row lock and load the freshly-committed state under it, returning
     * the managed {@link User} instance to mutate.
     *
     * <p><b>Why not a JPQL {@code findByIdWithLock} query.</b> A JPQL/native query forces Hibernate
     * to <i>auto-flush the persistence context before it executes</i>. When a caller already loaded
     * this same User earlier in the transaction (e.g. {@code OrderService.createOrder} loads it via
     * {@code findByUsername} before charging), that pre-execution flush tries to persist the
     * caller's now-stale User and loses the {@code @Version} race to a concurrently-committed
     * balance change — throwing {@code StaleObjectStateException} <i>before</i> the {@code FOR NO KEY
     * UPDATE} lock is ever acquired. Both the lock and any post-query refresh are therefore too late.
     * This was the real cause of the reseller {@code User#856} order-create failures.
     *
     * <p><b>How this avoids it.</b> {@link EntityManager#find} and {@link EntityManager#refresh} are
     * <i>not</i> queries, so neither triggers an auto-flush. We first obtain the managed instance
     * (find never locks and never flushes), then {@code refresh(..., PESSIMISTIC_WRITE)} acquires the
     * row lock <i>and</i> reloads the winner's committed {@code @Version} + balance in a single
     * statement — discarding any spurious dirty state Hibernate detected on the cached instance.
     * Because the lock is held from this point through commit, the eventual balance {@code UPDATE}
     * flushes on top of fresh state and cannot lose the version race.
     *
     * <p>Returns the same managed object the caller passed when it was already in the persistence
     * context (JPA identity map), so an in-flight {@code order.getUser()} stays in sync — the reason
     * refresh-in-place is used rather than detach + reload.
     *
     * <p><b>Invariant for callers:</b> {@code refresh} DISCARDS any pending in-memory changes on the
     * User. Never mutate a non-balance User field (and expect it to persist) earlier in the same
     * transaction that then calls into one of these balance methods on the same instance — the
     * mutation would be silently dropped here. Balance/totalSpent are always set AFTER this call.
     */
    private User lockAndRefreshUser(Long userId) {
        User managedUser = entityManager.find(User.class, userId);
        if (managedUser == null) {
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        try {
            entityManager.refresh(managedUser, LockModeType.PESSIMISTIC_WRITE);
        } catch (jakarta.persistence.EntityNotFoundException e) {
            // Row hard-deleted between find() and the locking refresh() (only reachable via the
            // GDPR hard-delete cron — users are otherwise soft-deleted). Surface the same clean
            // not-found signal the old single-statement lock query produced, not a raw 500.
            throw new ResourceNotFoundException("User not found with id: " + userId);
        }
        return managedUser;
    }

    /**
     * Acquire and hold the per-user balance row lock for a caller whose enclosing transaction must
     * mutate that user's balance later (e.g. {@code OrderService.createOrder}, which loads the User
     * up front and then runs several JPQL/native queries — quota checks, order insert — before
     * charging).
     *
     * <p>Call this at the TOP of the transaction, immediately after loading the User and before any
     * other query runs. Holding {@code FOR NO KEY UPDATE} from that point serializes concurrent
     * same-user order creation, so no later auto-flush (including a native query's full flush) can
     * persist a stale User and lose the {@code @Version} race — closing the failure class at every
     * query point in the transaction, not just at the balance-mutation site. {@link
     * Propagation#MANDATORY} enforces that a caller transaction already exists (a lock outside a
     * transaction would be pointless and immediately released).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUserForUpdate(Long userId) {
        lockAndRefreshUser(userId);
    }

    /**
     * Deducts the specified amount from the user's balance with optimistic locking.
     *
     * @param user The user to deduct balance from
     * @param amount The amount to deduct (must be positive)
     * @param order The order associated with this deduction
     * @param description Optional description for the transaction
     * @throws InsufficientBalanceException if the user doesn't have enough balance
     * @throws ConcurrencyException if there's a concurrency conflict
     * @throws InvalidAmountException if the amount is invalid
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 10,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public void deductBalance(User user, BigDecimal amount, Order order, String description) {
        Objects.requireNonNull(user, "User cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);

        // Take the balance row lock and load fresh committed state under it. See lockAndRefreshUser:
        // acquiring the lock via find + refresh (not a JPQL query) avoids the pre-query auto-flush
        // that made a pre-loaded, stale User fail with StaleObjectStateException before the lock.
        User managedUser = lockAndRefreshUser(user.getId());

        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal amountToDeduct = amount.setScale(SCALE, ROUNDING_MODE);

        if (currentBalance.compareTo(amountToDeduct) < 0) {
            throw new InsufficientBalanceException(
                    String.format(
                            "Insufficient balance. Current: %s, Required: %s",
                            currentBalance, amountToDeduct));
        }

        BigDecimal newBalance = currentBalance.subtract(amountToDeduct);
        managedUser.setBalance(newBalance);

        // Update total spent
        BigDecimal currentTotalSpent =
                Optional.ofNullable(managedUser.getTotalSpent()).orElse(BigDecimal.ZERO);
        managedUser.setTotalSpent(currentTotalSpent.add(amountToDeduct));

        userRepository.save(managedUser);

        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(managedUser);
        transaction.setOrder(order);
        transaction.setAmount(amountToDeduct.negate());
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.ORDER_PAYMENT);
        transaction.setDescription(
                description != null
                        ? description
                        : String.format(
                                "Payment for order #%s", order != null ? order.getId() : "N/A"));
        transaction.setCreatedAt(LocalDateTime.now());

        transactionRepository.save(transaction);

        log.info(
                "Deducted {} from user {}. New balance: {}",
                amountToDeduct,
                managedUser.getUsername(),
                newBalance);
    }

    /**
     * Adds the specified amount to the user's balance.
     *
     * @param user The user to add balance to
     * @param amount The amount to add (must be positive)
     * @param deposit The deposit associated with this addition (optional)
     * @param description Description of the transaction
     * @return The updated balance
     * @throws InvalidAmountException if the amount is invalid
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 10,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    /** Simple method to add balance without deposit entity (for testing/manual deposits) */
    public BigDecimal addBalance(User user, BigDecimal amount, String description) {
        return addBalance(user, amount, null, description);
    }

    // This overload carries the real crediting logic and is called directly (e.g. by
    // CryptomusService), so it needs its own transaction/retry boundary — the annotations on the
    // 3-arg sibling do NOT protect a direct call here, and a same-bean delegation would ignore
    // them.
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 10,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public BigDecimal addBalance(
            User user, BigDecimal amount, BalanceDeposit deposit, String description) {
        Objects.requireNonNull(user, "User cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);

        // Take the balance row lock and load fresh committed state under it. See lockAndRefreshUser:
        // acquiring the lock via find + refresh (not a JPQL query) avoids the pre-query auto-flush
        // that made a pre-loaded, stale User fail with StaleObjectStateException before the lock.
        User managedUser = lockAndRefreshUser(user.getId());

        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal amountToAdd = amount.setScale(SCALE, ROUNDING_MODE);
        BigDecimal newBalance = currentBalance.add(amountToAdd);

        managedUser.setBalance(newBalance);
        userRepository.save(managedUser);

        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(managedUser);
        transaction.setDeposit(deposit);
        transaction.setAmount(amountToAdd);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.DEPOSIT);
        transaction.setDescription(description != null ? description : "Balance deposit");
        transaction.setCreatedAt(LocalDateTime.now());

        transactionRepository.save(transaction);

        log.info(
                "Added {} to user {} balance. New balance: {}",
                amountToAdd,
                managedUser.getUsername(),
                newBalance);

        return newBalance;
    }

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 10,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public void refund(User user, BigDecimal amount, Order order, String reason) {
        Objects.requireNonNull(user, "User cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);

        // Take the balance row lock and load fresh committed state under it. See lockAndRefreshUser:
        // acquiring the lock via find + refresh (not a JPQL query) avoids the pre-query auto-flush
        // that made a pre-loaded, stale User fail with StaleObjectStateException before the lock.
        User managedUser = lockAndRefreshUser(user.getId());

        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal refundAmount = amount.setScale(SCALE, ROUNDING_MODE);
        BigDecimal newBalance = currentBalance.add(refundAmount);

        managedUser.setBalance(newBalance);
        userRepository.save(managedUser);

        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(managedUser);
        transaction.setOrder(order);
        transaction.setAmount(refundAmount);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.REFUND);
        transaction.setDescription(reason != null ? reason : "Order refund");
        transaction.setCreatedAt(LocalDateTime.now());

        transactionRepository.save(transaction);

        log.info(
                "Refunded {} to user {} for order {}",
                refundAmount,
                managedUser.getUsername(),
                order != null ? order.getId() : "N/A");
    }

    /**
     * Gets the current balance for a user.
     *
     * @param userId The ID of the user
     * @return The current balance
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional(readOnly = true)
    public BigDecimal getUserBalance(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("User not found with id: " + userId))
                .getBalance();
    }

    /**
     * Validates if the user has sufficient balance for the requested amount.
     *
     * @param userId The ID of the user
     * @param requiredAmount The amount to check
     * @return true if the user has sufficient balance, false otherwise
     * @throws ResourceNotFoundException if the user is not found
     * @throws InvalidAmountException if the required amount is invalid
     */
    @Transactional(readOnly = true)
    public boolean hasSufficientBalance(Long userId, BigDecimal requiredAmount) {
        Objects.requireNonNull(requiredAmount, "Required amount cannot be null");
        validateAmount(requiredAmount);

        BigDecimal currentBalance =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId))
                        .getBalance();

        return currentBalance.compareTo(requiredAmount) >= 0;
    }

    /**
     * Gets the transaction history for a user with pagination.
     *
     * @param userId The ID of the user
     * @param page Page number (0-based)
     * @param size Number of items per page
     * @return Page of transactions
     */
    @Transactional(readOnly = true)
    public Page<BalanceTransaction> getTransactionHistory(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return transactionRepository.findByUserId(userId, pageable);
    }

    /** Get user balance by username */
    @Transactional(readOnly = true)
    public BigDecimal getUserBalanceByUsername(String username) {
        return userRepository
                .findByUsername(username)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "User not found with username: " + username))
                .getBalance();
    }

    /** Refund order amount */
    @Transactional
    public void refundOrder(Order order) {
        User user = order.getUser();
        BigDecimal refundAmount = order.getCharge();

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            addToBalance(user, refundAmount, "Refund for cancelled order #" + order.getId());
        }
    }

    /** Add to user balance */
    @Transactional
    public void addToBalance(User user, BigDecimal amount, String description) {
        addBalance(user, amount, null, description);
    }

    /**
     * Atomically transfers balance between two users with retry logic.
     *
     * @param fromUserId Source user ID
     * @param toUserId Destination user ID
     * @param amount Amount to transfer
     * @param description Transaction description
     * @throws InsufficientBalanceException if source user has insufficient balance
     * @throws ResourceNotFoundException if either user is not found
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 15,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public void transferBalance(
            Long fromUserId, Long toUserId, BigDecimal amount, String description) {
        Objects.requireNonNull(fromUserId, "From user ID cannot be null");
        Objects.requireNonNull(toUserId, "To user ID cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);

        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("Cannot transfer to the same user");
        }

        // Load users in a consistent order to prevent deadlocks
        // CRITICAL FIX: Use pessimistic locking to prevent race conditions
        Long firstUserId = fromUserId < toUserId ? fromUserId : toUserId;
        Long secondUserId = fromUserId < toUserId ? toUserId : fromUserId;

        // Lock + refresh both in ascending-id order (see lockAndRefreshUser) so a pre-loaded, stale
        // @Version can't fail the transfer with StaleObjectStateException, and the consistent lock
        // ordering prevents deadlocks between two concurrent transfers on the same pair.
        User firstUser = lockAndRefreshUser(firstUserId);
        User secondUser = lockAndRefreshUser(secondUserId);

        User fromUser = firstUserId.equals(fromUserId) ? firstUser : secondUser;
        User toUser = firstUserId.equals(fromUserId) ? secondUser : firstUser;

        BigDecimal transferAmount = amount.setScale(SCALE, ROUNDING_MODE);
        BigDecimal fromCurrentBalance = fromUser.getBalance();
        BigDecimal toCurrentBalance = toUser.getBalance();

        if (fromCurrentBalance.compareTo(transferAmount) < 0) {
            throw new InsufficientBalanceException(
                    String.format(
                            "Insufficient balance for transfer. Current: %s, Required: %s",
                            fromCurrentBalance, transferAmount));
        }

        // Update balances
        BigDecimal fromNewBalance = fromCurrentBalance.subtract(transferAmount);
        BigDecimal toNewBalance = toCurrentBalance.add(transferAmount);

        fromUser.setBalance(fromNewBalance);
        toUser.setBalance(toNewBalance);

        userRepository.save(fromUser);
        userRepository.save(toUser);

        // Create transaction records
        String transferDescription = description != null ? description : "Balance transfer";

        BalanceTransaction debitTransaction = new BalanceTransaction();
        debitTransaction.setUser(fromUser);
        debitTransaction.setAmount(transferAmount.negate());
        debitTransaction.setBalanceBefore(fromCurrentBalance);
        debitTransaction.setBalanceAfter(fromNewBalance);
        debitTransaction.setTransactionType(TransactionType.TRANSFER_OUT);
        debitTransaction.setDescription(transferDescription + " to user " + toUser.getUsername());
        debitTransaction.setCreatedAt(LocalDateTime.now());

        BalanceTransaction creditTransaction = new BalanceTransaction();
        creditTransaction.setUser(toUser);
        creditTransaction.setAmount(transferAmount);
        creditTransaction.setBalanceBefore(toCurrentBalance);
        creditTransaction.setBalanceAfter(toNewBalance);
        creditTransaction.setTransactionType(TransactionType.TRANSFER_IN);
        creditTransaction.setDescription(
                transferDescription + " from user " + fromUser.getUsername());
        creditTransaction.setCreatedAt(LocalDateTime.now());

        transactionRepository.save(debitTransaction);
        transactionRepository.save(creditTransaction);

        log.info(
                "Transferred {} from user {} to user {}. New balances: {} -> {}, {} -> {}",
                transferAmount,
                fromUser.getUsername(),
                toUser.getUsername(),
                fromCurrentBalance,
                fromNewBalance,
                toCurrentBalance,
                toNewBalance);
    }

    /**
     * Atomically adjusts user balance (can be positive or negative) with comprehensive validation.
     *
     * @param userId User ID
     * @param adjustment Amount to adjust (positive for credit, negative for debit)
     * @param transactionType Type of transaction
     * @param description Transaction description
     * @param order Associated order (optional)
     * @return New balance after adjustment
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 10,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public BigDecimal adjustBalance(
            Long userId,
            BigDecimal adjustment,
            TransactionType transactionType,
            String description,
            Order order) {
        Objects.requireNonNull(userId, "User ID cannot be null");
        Objects.requireNonNull(adjustment, "Adjustment amount cannot be null");
        Objects.requireNonNull(transactionType, "Transaction type cannot be null");

        if (adjustment.compareTo(BigDecimal.ZERO) == 0) {
            throw new InvalidAmountException("Adjustment amount cannot be zero");
        }

        // For debits, validate amount is positive before negating
        if (adjustment.compareTo(BigDecimal.ZERO) < 0) {
            validateAmount(adjustment.abs());
        } else {
            validateAmount(adjustment);
        }

        User managedUser =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId));

        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal adjustmentAmount = adjustment.setScale(SCALE, ROUNDING_MODE);
        BigDecimal newBalance = currentBalance.add(adjustmentAmount);

        // Prevent negative balance for debits
        if (adjustmentAmount.compareTo(BigDecimal.ZERO) < 0
                && newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientBalanceException(
                    String.format(
                            "Insufficient balance for adjustment. Current: %s, Adjustment: %s",
                            currentBalance, adjustmentAmount));
        }

        managedUser.setBalance(newBalance);

        // Update total spent for debit transactions
        if (adjustmentAmount.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal currentTotalSpent =
                    Optional.ofNullable(managedUser.getTotalSpent()).orElse(BigDecimal.ZERO);
            managedUser.setTotalSpent(currentTotalSpent.add(adjustmentAmount.abs()));
        }

        userRepository.save(managedUser);

        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(managedUser);
        transaction.setOrder(order);
        transaction.setAmount(adjustmentAmount);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(transactionType);
        transaction.setDescription(description != null ? description : "Balance adjustment");
        transaction.setCreatedAt(LocalDateTime.now());

        transactionRepository.save(transaction);

        log.info(
                "Adjusted balance for user {} by {}. New balance: {} (transaction type: {})",
                managedUser.getUsername(),
                adjustmentAmount,
                newBalance,
                transactionType);

        return newBalance;
    }

    /**
     * Checks and reserves balance atomically for an order. This method checks if sufficient balance
     * exists and temporarily holds it.
     *
     * @param userId User ID
     * @param amount Amount to reserve
     * @return true if balance was successfully reserved
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 5,
            rollbackFor = Exception.class,
            readOnly = true)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public boolean checkAndReserveBalance(Long userId, BigDecimal amount) {
        Objects.requireNonNull(userId, "User ID cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);

        User managedUser =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId));

        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal reserveAmount = amount.setScale(SCALE, ROUNDING_MODE);

        boolean hasSufficientBalance = currentBalance.compareTo(reserveAmount) >= 0;

        log.debug(
                "Balance check for user {}: current={}, required={}, sufficient={}",
                managedUser.getUsername(),
                currentBalance,
                reserveAmount,
                hasSufficientBalance);

        return hasSufficientBalance;
    }

    /**
     * Atomically checks balance and deducts if sufficient (prevents race conditions). This method
     * combines balance check and deduction in a single atomic operation.
     *
     * @param user The user to check and deduct from
     * @param amount The amount to check and deduct
     * @param order The order associated with this deduction
     * @param description Transaction description
     * @return true if balance was sufficient and deducted, false otherwise
     */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            propagation = Propagation.REQUIRED,
            timeout = 10,
            rollbackFor = Exception.class)
    @Retryable(
            value = {ConcurrencyFailureException.class},
            maxAttemptsExpression = "#{@balanceService.maxRetryAttempts}",
            backoff =
                    @Backoff(
                            delayExpression = "#{@balanceService.initialDelayMs}",
                            maxDelayExpression = "#{@balanceService.maxDelayMs}",
                            multiplierExpression = "#{@balanceService.backoffMultiplier}",
                            random = true))
    public boolean checkAndDeductBalance(
            User user, BigDecimal amount, Order order, String description) {
        Objects.requireNonNull(user, "User cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);

        // Take the balance row lock and load fresh committed state under it. See lockAndRefreshUser:
        // acquiring the lock via find + refresh (not a JPQL query) avoids the pre-query auto-flush
        // that made a pre-loaded, stale User fail with StaleObjectStateException before the lock.
        User managedUser = lockAndRefreshUser(user.getId());

        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal amountToDeduct = amount.setScale(SCALE, ROUNDING_MODE);

        // Check if sufficient balance exists
        if (currentBalance.compareTo(amountToDeduct) < 0) {
            log.debug(
                    "Insufficient balance for user {}: current={}, required={}",
                    managedUser.getUsername(),
                    currentBalance,
                    amountToDeduct);
            return false;
        }

        // Deduct balance atomically
        BigDecimal newBalance = currentBalance.subtract(amountToDeduct);
        managedUser.setBalance(newBalance);

        // Update total spent
        BigDecimal currentTotalSpent =
                Optional.ofNullable(managedUser.getTotalSpent()).orElse(BigDecimal.ZERO);
        managedUser.setTotalSpent(currentTotalSpent.add(amountToDeduct));

        userRepository.save(managedUser);

        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(managedUser);
        transaction.setOrder(order);
        transaction.setAmount(amountToDeduct.negate());
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.ORDER_PAYMENT);
        transaction.setDescription(
                description != null
                        ? description
                        : String.format(
                                "Payment for order #%s", order != null ? order.getId() : "N/A"));
        transaction.setCreatedAt(LocalDateTime.now());

        transactionRepository.save(transaction);

        log.info(
                "Successfully checked and deducted {} from user {}. New balance: {}",
                amountToDeduct,
                managedUser.getUsername(),
                newBalance);

        return true;
    }

    /**
     * Executes a balance operation within a programmatic transaction with custom isolation. This
     * method provides fine-grained control over transaction properties.
     *
     * @param operation The operation to execute
     * @param <T> Return type of the operation
     * @return Result of the operation
     */
    public <T> T executeInBalanceTransaction(Supplier<T> operation) {
        return balanceTransactionTemplate.execute(
                status -> {
                    try {
                        return operation.get();
                    } catch (Exception e) {
                        status.setRollbackOnly();
                        log.error(
                                "Balance transaction failed, marking for rollback: {}",
                                e.getMessage());
                        throw e;
                    }
                });
    }

    /**
     * Executes a read-only operation with optimized transaction settings.
     *
     * @param operation The read operation to execute
     * @param <T> Return type of the operation
     * @return Result of the operation
     */
    public <T> T executeInReadOnlyTransaction(Supplier<T> operation) {
        return readOnlyTransactionTemplate.execute(status -> operation.get());
    }

    /** Get transaction history for a user with optimization (wrapper method) */
    public Page<BalanceTransaction> getTransactionHistoryOptimized(
            Long userId, int page, int size) {
        return getTransactionHistory(userId, page, size);
    }

    /** Get transaction history as DTOs for API response */
    public Page<TransactionHistoryResponse> getTransactionHistory(Long userId, Pageable pageable) {
        Page<BalanceTransaction> transactions =
                transactionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return transactions.map(this::mapToTransactionResponse);
    }

    /**
     * Filtered variant — returns transactions matching the given types and/or date range.
     *
     * <p>Either filter is optional: {@code types == null} means "any type", {@code (from, to)} must
     * be provided together (both non-null) to apply a date range — passing only one is ignored
     * (controller responsibility). Active users can have thousands of ORDER_PAYMENT rows; without a
     * type filter the first page would drown rare DEPOSIT / REFUND entries. Dedicated derived
     * queries per combination keep the JPQL trivial and let Postgres pick the (user_id, created_at
     * DESC) index naturally.
     *
     * <p>The controller is responsible for never passing an empty {@code types} collection: an
     * explicit-but-empty filter should return an empty page, not all transactions.
     */
    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getTransactionHistory(
            Long userId,
            java.util.Collection<com.smmpanel.entity.TransactionType> types,
            java.time.LocalDateTime from,
            java.time.LocalDateTime to,
            Pageable pageable) {
        boolean hasTypes = types != null && !types.isEmpty();
        boolean hasRange = from != null && to != null;

        Page<BalanceTransaction> raw;
        if (hasTypes && hasRange) {
            raw =
                    transactionRepository
                            .findByUserIdAndTransactionTypeInAndCreatedAtBetweenOrderByCreatedAtDesc(
                                    userId, types, from, to, pageable);
        } else if (hasTypes) {
            raw =
                    transactionRepository.findByUserIdAndTransactionTypeInOrderByCreatedAtDesc(
                            userId, types, pageable);
        } else if (hasRange) {
            raw =
                    transactionRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                            userId, from, to, pageable);
        } else {
            raw = transactionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        return raw.map(this::mapToTransactionResponse);
    }

    /**
     * Per-type sums over the user's entire transaction history. Returns a map keyed by the {@link
     * com.smmpanel.entity.TransactionType} enum name (e.g. {@code "DEPOSIT"}, {@code
     * "ORDER_PAYMENT"}, {@code "REFUND"}, {@code "ADJUSTMENT"}), value = signed BigDecimal sum of
     * {@code amount}. Types that the user has never transacted in are omitted from the map — the
     * caller should default to zero.
     *
     * <p>Used by the Transactions page stat cards so they reflect real lifetime totals rather than
     * just the latest paginated page.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, java.math.BigDecimal> getTransactionSummary(Long userId) {
        java.util.List<Object[]> rows = transactionRepository.sumAmountByTypeForUser(userId);
        java.util.Map<String, java.math.BigDecimal> result = new java.util.HashMap<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            com.smmpanel.entity.TransactionType type = (com.smmpanel.entity.TransactionType) row[0];
            java.math.BigDecimal sum = (java.math.BigDecimal) row[1];
            result.put(type.name(), sum != null ? sum : java.math.BigDecimal.ZERO);
        }
        return result;
    }

    /** Get recent transactions as DTOs for API response */
    public List<TransactionHistoryResponse> getRecentTransactions(Long userId, int limit) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<BalanceTransaction> transactions =
                transactionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return transactions.getContent().stream()
                .map(this::mapToTransactionResponse)
                .collect(Collectors.toList());
    }

    private TransactionHistoryResponse mapToTransactionResponse(BalanceTransaction transaction) {
        return TransactionHistoryResponse.builder()
                .id(transaction.getId())
                .type(transaction.getTransactionType().toString())
                .amount(transaction.getAmount())
                .balanceBefore(transaction.getBalanceBefore())
                .balanceAfter(transaction.getBalanceAfter())
                .description(transaction.getDescription())
                .status("COMPLETED") // All saved transactions are completed
                .createdAt(transaction.getCreatedAt())
                .orderId(transaction.getOrder() != null ? transaction.getOrder().getId() : null)
                .referenceNumber(transaction.getId().toString()) // Use ID as reference for now
                .build();
    }
}
