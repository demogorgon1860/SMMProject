package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.exception.*;
import com.smmpanel.repository.BalanceTransactionRepository;
import com.smmpanel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {
    
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int SCALE = 8;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    
    private final UserRepository userRepository;
    private final BalanceTransactionRepository transactionRepository;
    
    /**
     * Validates the amount is positive and has proper scale
     */
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        if (amount.scale() > SCALE) {
            throw new InvalidAmountException("Amount scale exceeds maximum of " + SCALE + " decimal places");
        }
    }

    /**
     * Deducts the specified amount from the user's balance with optimistic locking.
     * @param user The user to deduct balance from
     * @param amount The amount to deduct (must be positive)
     * @param order The order associated with this deduction
     * @param description Optional description for the transaction
     * @throws InsufficientBalanceException if the user doesn't have enough balance
     * @throws ConcurrencyException if there's a concurrency conflict
     * @throws InvalidAmountException if the amount is invalid
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Retryable(value = { ObjectOptimisticLockingFailureException.class },
               maxAttempts = MAX_RETRY_ATTEMPTS,
               backoff = @Backoff(delay = 100))
    public void deductBalance(User user, BigDecimal amount, Order order, String description) {
        Objects.requireNonNull(user, "User cannot be null");
        Objects.requireNonNull(amount, "Amount cannot be null");
        validateAmount(amount);
        
        // Reload user with the latest version to handle concurrent updates
        User managedUser = userRepository.findById(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + user.getId()));
        
        BigDecimal currentBalance = managedUser.getBalance();
        BigDecimal amountToDeduct = amount.setScale(SCALE, ROUNDING_MODE);
        
        if (currentBalance.compareTo(amountToDeduct) < 0) {
            throw new InsufficientBalanceException(
                String.format("Insufficient balance. Current: %s, Required: %s", 
                    currentBalance, amountToDeduct));
        }
        
        BigDecimal newBalance = currentBalance.subtract(amountToDeduct);
        managedUser.setBalance(newBalance);
        
        // Update total spent
        BigDecimal currentTotalSpent = Optional.ofNullable(managedUser.getTotalSpent())
                .orElse(BigDecimal.ZERO);
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
        transaction.setDescription(description != null ? description : 
            String.format("Payment for order #%s", order != null ? order.getId() : "N/A"));
        transaction.setCreatedAt(LocalDateTime.now());
        
        transactionRepository.save(transaction);
        
        log.info("Deducted {} from user {}. New balance: {}", 
            amountToDeduct, managedUser.getUsername(), newBalance);
    }

    /**
     * Adds the specified amount to the user's balance.
     * @param user The user to add balance to
     * @param amount The amount to add (must be positive)
     * @param deposit The deposit associated with this addition (optional)
     * @param description Description of the transaction
     * @return The updated balance
     * @throws InvalidAmountException if the amount is invalid
     */
    @Transactional
    public BigDecimal addBalance(User user, BigDecimal amount, BalanceDeposit deposit, String description) {
        BigDecimal currentBalance = user.getBalance();
        BigDecimal newBalance = currentBalance.add(amount);
        
        user.setBalance(newBalance);
        userRepository.save(user);
        
        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(user);
        transaction.setDeposit(deposit);
        transaction.setAmount(amount);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.DEPOSIT);
        transaction.setDescription(description);
        transaction.setCreatedAt(LocalDateTime.now());
        
        transactionRepository.save(transaction);
        
        log.info("Added {} to user {} balance. New balance: {}", amount, user.getUsername(), newBalance);
        
        return newBalance;
    }

    @Transactional
    public void refund(User user, BigDecimal amount, Order order, String reason) {
        BigDecimal currentBalance = user.getBalance();
        BigDecimal newBalance = currentBalance.add(amount);
        
        user.setBalance(newBalance);
        userRepository.save(user);
        
        // Create transaction record
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(user);
        transaction.setOrder(order);
        transaction.setAmount(amount);
        transaction.setBalanceBefore(currentBalance);
        transaction.setBalanceAfter(newBalance);
        transaction.setTransactionType(TransactionType.REFUND);
        transaction.setDescription(reason);
        
        transactionRepository.save(transaction);
        
        log.info("Refunded {} to user {} for order {}", amount, user.getUsername(), order.getId());
    }

    /**
     * Gets the current balance for a user.
     * @param userId The ID of the user
     * @return The current balance
     * @throws ResourceNotFoundException if the user is not found
     */
    @Transactional(readOnly = true)
    public BigDecimal getUserBalance(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId))
                .getBalance();
    }
    
    /**
     * Validates if the user has sufficient balance for the requested amount.
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
        
        BigDecimal currentBalance = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId))
                .getBalance();
                
        return currentBalance.compareTo(requiredAmount) >= 0;
    }
    
    /**
     * Gets the transaction history for a user with pagination.
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
}