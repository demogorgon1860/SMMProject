package com.smmpanel.service;

import com.smmpanel.entity.User;
import com.smmpanel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final OptimisticLockingService optimisticLockingService;

    public String getUsernameByApiKey(String apiKey) {
        // Use optimized query that only returns active users
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key or account disabled"));
        return user.getUsername();
    }

    public void validateApiKey(String apiKey) {
        getUsernameByApiKey(apiKey); // Will throw if invalid
    }

    public User getUserByApiKey(String apiKey) {
        return userRepository.findByApiKeyHashAndIsActiveTrue(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key or account disabled"));
    }

    /**
     * Update user balance with optimistic locking protection
     * 
     * @param userId User ID to update
     * @param amount Amount to add (positive) or subtract (negative)
     * @param description Description of the balance change
     * @return Updated user
     * @throws OptimisticLockingFailureException if concurrent modification detected
     */
    @Transactional
    public User updateBalanceWithLocking(Long userId, BigDecimal amount, String description) {
        return optimisticLockingService.handleBalanceUpdate(userId, () -> {
            // Load fresh user data
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
            
            log.debug("Updating balance for user {} (version {}): {} -> {}", 
                    userId, user.getVersion(), user.getBalance(), user.getBalance().add(amount));
            
            // Update balance using entity methods (which include validation)
            if (amount.compareTo(BigDecimal.ZERO) >= 0) {
                user.addBalance(amount);
            } else {
                user.subtractBalance(amount.abs());
            }
            
            // Save and return updated user
            User savedUser = userRepository.save(user);
            
            log.info("Balance updated for user {} (version {} -> {}): {} ({})", 
                    userId, user.getVersion(), savedUser.getVersion(), 
                    savedUser.getBalance(), description);
            
            return savedUser;
        });
    }

    /**
     * Update user profile with optimistic locking protection
     * 
     * @param userId User ID to update
     * @param updates Function to apply updates to the user
     * @return Updated user
     */
    @Transactional
    public User updateUserWithLocking(Long userId, java.util.function.Function<User, User> updates) {
        return optimisticLockingService.executeWithRetry(() -> {
            // Load fresh user data
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
            
            log.debug("Updating user {} (version {})", userId, user.getVersion());
            
            // Apply updates
            User updatedUser = updates.apply(user);
            
            // Save and return
            User savedUser = userRepository.save(updatedUser);
            
            log.info("User updated {} (version {} -> {})", 
                    userId, user.getVersion(), savedUser.getVersion());
            
            return savedUser;
        }, "User", userId);
    }

    /**
     * Deduct balance for order creation with optimistic locking
     * 
     * @param userId User ID
     * @param orderAmount Amount to deduct
     * @param orderId Order ID for reference
     * @return Updated user
     */
    @Transactional
    public User deductBalanceForOrder(Long userId, BigDecimal orderAmount, Long orderId) {
        return updateBalanceWithLocking(userId, orderAmount.negate(), 
                "Order payment - Order ID: " + orderId);
    }

    /**
     * Refund balance for cancelled order with optimistic locking
     * 
     * @param userId User ID
     * @param refundAmount Amount to refund
     * @param orderId Order ID for reference
     * @return Updated user
     */
    @Transactional
    public User refundBalanceForOrder(Long userId, BigDecimal refundAmount, Long orderId) {
        return updateBalanceWithLocking(userId, refundAmount, 
                "Order refund - Order ID: " + orderId);
    }

    /**
     * Add deposit to user balance with optimistic locking
     * 
     * @param userId User ID
     * @param depositAmount Amount to deposit
     * @param transactionId Transaction ID for reference
     * @return Updated user
     */
    @Transactional
    public User addDeposit(Long userId, BigDecimal depositAmount, String transactionId) {
        return updateBalanceWithLocking(userId, depositAmount, 
                "Deposit - Transaction ID: " + transactionId);
    }

    /**
     * Get user with version for optimistic locking
     * 
     * @param userId User ID
     * @return User with current version
     */
    @Transactional(readOnly = true)
    public Optional<User> getUserWithVersion(Long userId) {
        return userRepository.findById(userId);
    }

    /**
     * Check if user has sufficient balance (thread-safe read)
     * 
     * @param userId User ID
     * @param requiredAmount Amount needed
     * @return true if user has sufficient balance
     */
    @Transactional(readOnly = true)
    public boolean hasSufficientBalance(Long userId, BigDecimal requiredAmount) {
        return userRepository.findById(userId)
                .map(user -> user.hasSufficientBalance(requiredAmount))
                .orElse(false);
    }
} 