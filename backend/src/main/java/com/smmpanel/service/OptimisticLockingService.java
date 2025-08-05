package com.smmpanel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Service to handle optimistic locking conflicts with retry mechanism
 * Provides utilities for handling concurrent entity modifications
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OptimisticLockingService {

    /**
     * Execute an operation with automatic retry on optimistic locking failures
     * 
     * @param operation The operation to execute
     * @param maxRetries Maximum number of retry attempts
     * @param entityType Type of entity for logging purposes
     * @param entityId ID of entity for logging purposes
     * @return Result of the operation
     * @throws OptimisticLockingException if all retry attempts fail
     */
    @Retryable(
        value = {OptimisticLockingFailureException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 100, multiplier = 2)
    )
    public <T> T executeWithRetry(Supplier<T> operation, String entityType, Object entityId) {
        try {
            log.debug("Executing operation for {} with ID: {}", entityType, entityId);
            return operation.get();
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic locking failure for {} with ID: {}. Retrying...", 
                    entityType, entityId);
            throw e; // Will be retried by @Retryable
        }
    }

    /**
     * Execute an operation with manual retry logic for more control
     * 
     * @param operation The operation to execute
     * @param maxRetries Maximum number of retry attempts
     * @param entityType Type of entity for logging purposes
     * @param entityId ID of entity for logging purposes
     * @return Result of the operation
     * @throws OptimisticLockingException if all retry attempts fail
     */
    public <T> T executeWithManualRetry(Supplier<T> operation, int maxRetries, 
                                       String entityType, Object entityId) {
        int attempt = 0;
        OptimisticLockingFailureException lastException = null;
        
        while (attempt < maxRetries) {
            try {
                log.debug("Executing operation for {} with ID: {} (attempt {}/{})", 
                        entityType, entityId, attempt + 1, maxRetries);
                return operation.get();
                
            } catch (OptimisticLockingFailureException e) {
                lastException = e;
                attempt++;
                
                if (attempt < maxRetries) {
                    long delay = calculateBackoffDelay(attempt);
                    log.warn("Optimistic locking failure for {} with ID: {} (attempt {}/{}). " +
                            "Retrying in {}ms...", 
                            entityType, entityId, attempt, maxRetries, delay);
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new OptimisticLockingException(
                            "Retry interrupted for " + entityType + " with ID: " + entityId, ie);
                    }
                } else {
                    log.error("All retry attempts failed for {} with ID: {} after {} attempts", 
                            entityType, entityId, maxRetries);
                }
            }
        }
        
        throw new OptimisticLockingException(
            "Failed to execute operation for " + entityType + " with ID: " + entityId + 
            " after " + maxRetries + " attempts", lastException);
    }

    /**
     * Handle balance update with optimistic locking retry
     * 
     * @param userId User ID for balance update
     * @param balanceUpdateOperation Operation to update balance
     * @return Result of balance update
     */
    @Transactional
    public <T> T handleBalanceUpdate(Long userId, Supplier<T> balanceUpdateOperation) {
        return executeWithRetry(balanceUpdateOperation, "User", userId);
    }

    /**
     * Handle order status update with optimistic locking retry
     * 
     * @param orderId Order ID for status update
     * @param orderUpdateOperation Operation to update order
     * @return Result of order update
     */
    @Transactional
    public <T> T handleOrderUpdate(Long orderId, Supplier<T> orderUpdateOperation) {
        return executeWithRetry(orderUpdateOperation, "Order", orderId);
    }

    /**
     * Calculate exponential backoff delay
     * 
     * @param attempt Current attempt number (1-based)
     * @return Delay in milliseconds
     */
    private long calculateBackoffDelay(int attempt) {
        // Exponential backoff: 100ms, 200ms, 400ms, etc.
        return 100L * (1L << (attempt - 1));
    }

    /**
     * Create detailed conflict information for logging and debugging
     * 
     * @param entityType Type of entity
     * @param entityId Entity ID
     * @param expectedVersion Expected version
     * @param actualVersion Actual version found
     * @param operation Operation that was attempted
     * @return Conflict information string
     */
    public String createConflictInfo(String entityType, Object entityId, 
                                   Long expectedVersion, Long actualVersion, 
                                   String operation) {
        return String.format(
            "Optimistic locking conflict detected: %s[ID=%s] - Expected version: %d, " +
            "Actual version: %d, Operation: %s", 
            entityType, entityId, expectedVersion, actualVersion, operation
        );
    }

    /**
     * Custom exception for optimistic locking failures
     */
    public static class OptimisticLockingException extends RuntimeException {
        public OptimisticLockingException(String message) {
            super(message);
        }
        
        public OptimisticLockingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}