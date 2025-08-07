package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.exception.VideoProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * ERROR RECOVERY SERVICE
 * 
 * Provides comprehensive error recovery mechanisms:
 * 1. Automatic retry logic with exponential backoff
 * 2. Dead letter queue management for permanently failed orders
 * 3. Error status tracking and classification
 * 4. Manual retry functionality for operators
 * 5. Failure analysis and reporting
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorRecoveryService {

    private final OrderRepository orderRepository;
    private final OrderStateManagementService orderStateManagementService;

    @Value("${app.error-recovery.max-retries:3}")
    private int defaultMaxRetries;

    @Value("${app.error-recovery.initial-delay-minutes:5}")
    private int initialDelayMinutes;

    @Value("${app.error-recovery.max-delay-hours:24}")
    private int maxDelayHours;

    @Value("${app.error-recovery.backoff-multiplier:2.0}")
    private double backoffMultiplier;

    /**
     * RECORD ERROR WITH RETRY LOGIC
     * Records error and determines if retry should be attempted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ErrorRecoveryResult recordErrorAndScheduleRetry(Long orderId, String errorType, 
                                                          String errorMessage, String failedPhase, 
                                                          Throwable exception) {
        try {
            log.info("Recording error for order {}: type={}, phase={}", orderId, errorType, failedPhase);

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new VideoProcessingException("Order not found: " + orderId));

            // Update error tracking fields
            order.setRetryCount(order.getRetryCount() + 1);
            order.setLastErrorType(errorType);
            order.setFailureReason(errorMessage);
            order.setFailedPhase(failedPhase);
            order.setLastRetryAt(LocalDateTime.now());
            
            // Store stack trace for debugging
            if (exception != null) {
                order.setErrorStackTrace(getStackTraceString(exception));
            }

            // Determine if retry should be attempted
            boolean shouldRetry = shouldRetryOrder(order);
            
            if (shouldRetry) {
                // Calculate next retry time with exponential backoff
                LocalDateTime nextRetryTime = calculateNextRetryTime(order.getRetryCount());
                order.setNextRetryAt(nextRetryTime);
                order.setErrorMessage("Retry scheduled for: " + nextRetryTime + ". " + errorMessage);
                
                log.info("Scheduled retry for order {} at {} (attempt {}/{})", 
                        orderId, nextRetryTime, order.getRetryCount(), order.getMaxRetries());
                
                orderRepository.save(order);
                return ErrorRecoveryResult.retryScheduled(orderId, nextRetryTime, order.getRetryCount());
                
            } else {
                // Max retries exceeded - move to dead letter queue
                return moveToDeadLetterQueue(order, "Max retry attempts exceeded");
            }

        } catch (Exception e) {
            log.error("Failed to record error for order {}: {}", orderId, e.getMessage(), e);
            return ErrorRecoveryResult.failed(orderId, "Error recording failed: " + e.getMessage());
        }
    }

    /**
     * MOVE TO DEAD LETTER QUEUE
     * Permanently fails order and marks for manual review
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ErrorRecoveryResult moveToDeadLetterQueue(Order order, String reason) {
        try {
            log.warn("Moving order {} to dead letter queue: {}", order.getId(), reason);

            // Update order status to indicate permanent failure
            order.setStatus(OrderStatus.HOLDING);
            order.setIsManuallyFailed(true);
            order.setFailureReason(reason);
            order.setErrorMessage("DEAD LETTER QUEUE: " + reason + 
                    " (Retries: " + order.getRetryCount() + "/" + order.getMaxRetries() + ")");
            order.setNextRetryAt(null); // No more automatic retries

            orderRepository.save(order);

            // Remove from active processing
            orderStateManagementService.transitionToHolding(order.getId(), 
                    "Moved to dead letter queue: " + reason);

            log.warn("Order {} moved to dead letter queue after {} failed attempts", 
                    order.getId(), order.getRetryCount());

            return ErrorRecoveryResult.deadLetterQueue(order.getId(), reason, order.getRetryCount());

        } catch (Exception e) {
            log.error("Failed to move order {} to dead letter queue: {}", order.getId(), e.getMessage(), e);
            return ErrorRecoveryResult.failed(order.getId(), "Dead letter queue move failed: " + e.getMessage());
        }
    }

    /**
     * MANUAL RETRY FOR OPERATORS
     * Allows operators to manually retry failed orders
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ManualRetryResult manualRetry(Long orderId, String operatorNotes, boolean resetRetryCount) {
        try {
            log.info("Manual retry initiated for order {} by operator", orderId);

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new VideoProcessingException("Order not found: " + orderId));

            // Validate order can be retried
            if (!canManuallyRetry(order)) {
                return ManualRetryResult.failed(orderId, "Order cannot be manually retried in current state: " + order.getStatus());
            }

            // Reset retry tracking if requested
            if (resetRetryCount) {
                order.setRetryCount(0);
                order.setLastErrorType(null);
                order.setErrorStackTrace(null);
                log.info("Reset retry count for order {} as requested by operator", orderId);
            }

            // Update operator tracking
            order.setOperatorNotes(operatorNotes);
            order.setIsManuallyFailed(false);
            order.setNextRetryAt(LocalDateTime.now().plusMinutes(1)); // Immediate retry
            order.setLastRetryAt(LocalDateTime.now());

            // Reset order to processing state
            StateTransitionResult transition = orderStateManagementService
                    .validateAndUpdateOrderForProcessing(orderId, order.getYoutubeVideoId());

            if (!transition.isSuccess()) {
                return ManualRetryResult.failed(orderId, "Failed to transition order for retry: " + transition.getErrorMessage());
            }

            orderRepository.save(order);

            log.info("Manual retry scheduled for order {} with operator notes: {}", orderId, operatorNotes);
            return ManualRetryResult.success(orderId, operatorNotes, resetRetryCount);

        } catch (Exception e) {
            log.error("Manual retry failed for order {}: {}", orderId, e.getMessage(), e);
            return ManualRetryResult.failed(orderId, "Manual retry failed: " + e.getMessage());
        }
    }

    /**
     * SCHEDULED RETRY PROCESSING
     * Automatically processes orders scheduled for retry
     */
    @Scheduled(fixedDelayString = "${app.error-recovery.retry-processing-interval:300000}") // 5 minutes
    public void processScheduledRetriesScheduled() {
        processScheduledRetries();
    }

    /**
     * AUTOMATIC RETRY PROCESSING
     * Processes orders scheduled for retry
     */
    @Async("errorRecoveryExecutor")
    public CompletableFuture<Void> processScheduledRetries() {
        try {
            log.debug("Processing scheduled retries...");

            LocalDateTime now = LocalDateTime.now();
            Pageable pageable = PageRequest.of(0, 100); // Process in batches
            
            Page<Order> retryOrders = orderRepository.findOrdersReadyForRetry(now, pageable);
            
            if (retryOrders.hasContent()) {
                log.info("Found {} orders ready for retry", retryOrders.getNumberOfElements());
                
                for (Order order : retryOrders.getContent()) {
                    try {
                        processRetryOrder(order);
                    } catch (Exception e) {
                        log.error("Failed to process retry for order {}: {}", order.getId(), e.getMessage(), e);
                        // Record this retry failure
                        recordErrorAndScheduleRetry(order.getId(), "RETRY_PROCESSING_ERROR", 
                                e.getMessage(), "RETRY_EXECUTION", e);
                    }
                }
            }

            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            log.error("Error during scheduled retry processing: {}", e.getMessage(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * GET DEAD LETTER QUEUE ORDERS
     * Returns orders in dead letter queue for operator review
     */
    public Page<Order> getDeadLetterQueueOrders(Pageable pageable) {
        return orderRepository.findDeadLetterQueueOrders(pageable);
    }

    /**
     * GET ERROR STATISTICS
     * Returns error recovery statistics for monitoring
     */
    public ErrorRecoveryStats getErrorStatistics() {
        try {
            LocalDateTime past24Hours = LocalDateTime.now().minusHours(24);
            LocalDateTime pastWeek = LocalDateTime.now().minusWeeks(1);

            long totalFailedOrders = orderRepository.countFailedOrders();
            long failedLast24Hours = orderRepository.countFailedOrdersSince(past24Hours);
            long failedLastWeek = orderRepository.countFailedOrdersSince(pastWeek);
            long deadLetterQueueCount = orderRepository.countDeadLetterQueueOrders();
            long pendingRetries = orderRepository.countOrdersPendingRetry(LocalDateTime.now());

            List<ErrorTypeStats> errorTypeStats = orderRepository.getErrorTypeStatistics();

            return ErrorRecoveryStats.builder()
                    .totalFailedOrders(totalFailedOrders)
                    .failedLast24Hours(failedLast24Hours)
                    .failedLastWeek(failedLastWeek)
                    .deadLetterQueueCount(deadLetterQueueCount)
                    .pendingRetries(pendingRetries)
                    .errorTypeBreakdown(errorTypeStats)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get error statistics: {}", e.getMessage(), e);
            return ErrorRecoveryStats.empty();
        }
    }

    // Private helper methods

    private boolean shouldRetryOrder(Order order) {
        return order.getRetryCount() < order.getMaxRetries() && 
               !order.getIsManuallyFailed() &&
               order.getStatus() != OrderStatus.CANCELLED;
    }

    private boolean canManuallyRetry(Order order) {
        return order.getStatus() == OrderStatus.HOLDING || 
               order.getStatus() == OrderStatus.PROCESSING ||
               order.getIsManuallyFailed();
    }

    private LocalDateTime calculateNextRetryTime(int retryCount) {
        // Exponential backoff: 5min, 10min, 20min, 40min, etc. (capped at max delay)
        long delayMinutes = (long) (initialDelayMinutes * Math.pow(backoffMultiplier, retryCount - 1));
        long maxDelayMinutes = maxDelayHours * 60L;
        delayMinutes = Math.min(delayMinutes, maxDelayMinutes);
        
        return LocalDateTime.now().plusMinutes(delayMinutes);
    }

    private void processRetryOrder(Order order) {
        log.info("Processing retry for order {} (attempt {}/{})", 
                order.getId(), order.getRetryCount(), order.getMaxRetries());

        // Clear retry scheduling
        order.setNextRetryAt(null);
        order.setLastRetryAt(LocalDateTime.now());
        orderRepository.save(order);

        // Delegate to appropriate processing service based on failed phase
        String failedPhase = order.getFailedPhase();
        
        if ("VALIDATION".equals(failedPhase) || "VIDEO_ANALYSIS".equals(failedPhase)) {
            // Restart from beginning - queue for processing
            orderStateManagementService.validateAndUpdateOrderForProcessing(order.getId(), order.getYoutubeVideoId());
        } else {
            // Resume from failed phase - this would need integration with YouTubeAutomationService
            log.info("Resuming order {} from failed phase: {}", order.getId(), failedPhase);
            // This would trigger the appropriate service method based on the failed phase
        }
    }

    private String getStackTraceString(Throwable exception) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }
}

// Result classes for error recovery operations

/**
 * Error recovery operation result
 */
@lombok.Builder
@lombok.Data
public static class ErrorRecoveryResult {
    private final Long orderId;
    private final boolean success;
    private final String errorMessage;
    private final ErrorRecoveryAction action;
    private final LocalDateTime nextRetryTime;
    private final int retryCount;

    public static ErrorRecoveryResult retryScheduled(Long orderId, LocalDateTime nextRetryTime, int retryCount) {
        return ErrorRecoveryResult.builder()
                .orderId(orderId)
                .success(true)
                .action(ErrorRecoveryAction.RETRY_SCHEDULED)
                .nextRetryTime(nextRetryTime)
                .retryCount(retryCount)
                .build();
    }

    public static ErrorRecoveryResult deadLetterQueue(Long orderId, String reason, int retryCount) {
        return ErrorRecoveryResult.builder()
                .orderId(orderId)
                .success(true)
                .action(ErrorRecoveryAction.DEAD_LETTER_QUEUE)
                .errorMessage(reason)
                .retryCount(retryCount)
                .build();
    }

    public static ErrorRecoveryResult failed(Long orderId, String errorMessage) {
        return ErrorRecoveryResult.builder()
                .orderId(orderId)
                .success(false)
                .action(ErrorRecoveryAction.ERROR)
                .errorMessage(errorMessage)
                .build();
    }
}

/**
 * Manual retry operation result
 */
@lombok.Builder
@lombok.Data
public static class ManualRetryResult {
    private final Long orderId;
    private final boolean success;
    private final String errorMessage;
    private final String operatorNotes;
    private final boolean retryCountReset;

    public static ManualRetryResult success(Long orderId, String operatorNotes, boolean retryCountReset) {
        return ManualRetryResult.builder()
                .orderId(orderId)
                .success(true)
                .operatorNotes(operatorNotes)
                .retryCountReset(retryCountReset)
                .build();
    }

    public static ManualRetryResult failed(Long orderId, String errorMessage) {
        return ManualRetryResult.builder()
                .orderId(orderId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}

/**
 * Error recovery statistics
 */
@lombok.Builder
@lombok.Data
public static class ErrorRecoveryStats {
    private final long totalFailedOrders;
    private final long failedLast24Hours;
    private final long failedLastWeek;
    private final long deadLetterQueueCount;
    private final long pendingRetries;
    private final List<ErrorTypeStats> errorTypeBreakdown;

    public static ErrorRecoveryStats empty() {
        return ErrorRecoveryStats.builder()
                .totalFailedOrders(0)
                .failedLast24Hours(0)
                .failedLastWeek(0)
                .deadLetterQueueCount(0)
                .pendingRetries(0)
                .errorTypeBreakdown(List.of())
                .build();
    }
}

/**
 * Error type statistics
 */
@lombok.Builder
@lombok.Data
public static class ErrorTypeStats {
    private final String errorType;
    private final long count;
    private final double percentage;
}

/**
 * Error recovery actions
 */
enum ErrorRecoveryAction {
    RETRY_SCHEDULED,
    DEAD_LETTER_QUEUE,
    MANUAL_RETRY,
    ERROR
}