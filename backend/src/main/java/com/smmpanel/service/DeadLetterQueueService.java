package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.dto.kafka.VideoProcessingMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEAD LETTER QUEUE SERVICE
 * 
 * Handles permanently failed messages and orders:
 * 1. Consumes messages from dead letter queue topic
 * 2. Stores failed messages for operator review
 * 3. Provides dead letter queue management operations
 * 4. Tracks dead letter queue metrics and statistics
 * 5. Automated cleanup of old dead letter queue entries
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterQueueService {

    private final OrderRepository orderRepository;
    private final ErrorRecoveryService errorRecoveryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.dead-letter-queue.topic:video.processing.dlq}")
    private String deadLetterQueueTopic;

    @Value("${app.dead-letter-queue.retention-days:30}")
    private int retentionDays;

    @Value("${app.dead-letter-queue.max-entries:10000}")
    private int maxEntries;

    // Metrics tracking
    private final AtomicLong dlqMessagesReceived = new AtomicLong(0);
    private final AtomicLong dlqMessagesProcessed = new AtomicLong(0);
    private final AtomicLong dlqMessagesFailed = new AtomicLong(0);

    /**
     * DEAD LETTER QUEUE CONSUMER
     * Consumes messages that have permanently failed processing
     */
    @KafkaListener(
        topics = "${app.kafka.dead-letter-queue.topic:video.processing.dlq}",
        groupId = "${app.kafka.dead-letter-queue.consumer.group-id:dlq-processing-group}",
        containerFactory = "deadLetterQueueKafkaListenerContainerFactory"
    )
    public void processDeadLetterMessage(
            @Payload VideoProcessingMessage message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        try {
            dlqMessagesReceived.incrementAndGet();
            
            log.warn("Processing dead letter queue message: orderId={}, attempts={}, reason={}", 
                    message.getOrderId(), message.getAttemptNumber(), 
                    message.getMetadata().getOrDefault("dlq-reason", "unknown"));

            // Store dead letter queue entry
            DeadLetterQueueEntry dlqEntry = createDeadLetterQueueEntry(message, topic, partition, offset);
            storeDlqEntry(dlqEntry);

            // Update order status to reflect permanent failure
            updateOrderForDeadLetterQueue(message.getOrderId(), dlqEntry);

            // Send notification to operators if configured
            notifyOperatorsOfDeadLetterQueue(dlqEntry);

            dlqMessagesProcessed.incrementAndGet();
            acknowledgment.acknowledge();

            log.warn("Dead letter queue message processed: orderId={}", message.getOrderId());

        } catch (Exception e) {
            log.error("Failed to process dead letter queue message: orderId={}, error={}", 
                    message.getOrderId(), e.getMessage(), e);
            dlqMessagesFailed.incrementAndGet();
            acknowledgment.acknowledge(); // Acknowledge to prevent infinite reprocessing
        }
    }

    /**
     * SEND TO DEAD LETTER QUEUE
     * Manually send a message to the dead letter queue
     */
    public void sendToDeadLetterQueue(VideoProcessingMessage message, String reason) {
        try {
            log.warn("Sending message to dead letter queue: orderId={}, reason={}", 
                    message.getOrderId(), reason);

            // Add DLQ metadata
            message.addMetadata("dlq-timestamp", LocalDateTime.now().toString());
            message.addMetadata("dlq-reason", reason);
            message.addMetadata("dlq-final-attempt", message.getAttemptNumber().toString());

            // Send to DLQ topic
            kafkaTemplate.send(deadLetterQueueTopic, message.getRoutingKey(), message)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("Successfully sent message to DLQ: orderId={}", message.getOrderId());
                        } else {
                            log.error("Failed to send message to DLQ: orderId={}, error={}", 
                                    message.getOrderId(), ex.getMessage(), ex);
                        }
                    });

        } catch (Exception e) {
            log.error("Error sending message to dead letter queue: orderId={}, error={}", 
                    message.getOrderId(), e.getMessage(), e);
        }
    }

    /**
     * GET DEAD LETTER QUEUE ENTRIES
     * Retrieves dead letter queue entries for operator review
     */
    public Page<Order> getDeadLetterQueueOrders(Pageable pageable) {
        return orderRepository.findDeadLetterQueueOrders(pageable);
    }

    /**
     * GET DEAD LETTER QUEUE ENTRIES BY ERROR TYPE
     * Retrieves DLQ entries filtered by error type
     */
    public Page<Order> getDeadLetterQueueOrdersByErrorType(String errorType, Pageable pageable) {
        return orderRepository.findOrdersByErrorType(errorType, pageable);
    }

    /**
     * PURGE DEAD LETTER QUEUE ENTRY
     * Permanently removes a dead letter queue entry
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DlqOperationResult purgeDeadLetterQueueEntry(Long orderId, String operatorNotes) {
        try {
            log.info("Purging dead letter queue entry for order {}: {}", orderId, operatorNotes);

            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            if (!order.getIsManuallyFailed() && order.getRetryCount() < order.getMaxRetries()) {
                return DlqOperationResult.failed(orderId, "Order is not in dead letter queue");
            }

            // Update order status to cancelled (permanent removal)
            order.setStatus(OrderStatus.CANCELLED);
            order.setOperatorNotes(operatorNotes);
            order.setErrorMessage("PURGED FROM DLQ: " + operatorNotes);
            order.setUpdatedAt(LocalDateTime.now());

            orderRepository.save(order);

            log.info("Dead letter queue entry purged for order {}", orderId);
            return DlqOperationResult.success(orderId, "Entry purged successfully");

        } catch (Exception e) {
            log.error("Failed to purge dead letter queue entry for order {}: {}", orderId, e.getMessage(), e);
            return DlqOperationResult.failed(orderId, "Purge failed: " + e.getMessage());
        }
    }

    /**
     * REQUEUE FROM DEAD LETTER QUEUE
     * Moves order back to processing queue with reset retry count
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DlqOperationResult requeueFromDeadLetterQueue(Long orderId, String operatorNotes) {
        try {
            log.info("Requeuing order {} from dead letter queue: {}", orderId, operatorNotes);

            // Use manual retry functionality from ErrorRecoveryService
            ManualRetryResult retryResult = errorRecoveryService.manualRetry(orderId, operatorNotes, true);

            if (retryResult.isSuccess()) {
                log.info("Successfully requeued order {} from dead letter queue", orderId);
                return DlqOperationResult.success(orderId, "Order requeued successfully");
            } else {
                return DlqOperationResult.failed(orderId, "Requeue failed: " + retryResult.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("Failed to requeue order {} from dead letter queue: {}", orderId, e.getMessage(), e);
            return DlqOperationResult.failed(orderId, "Requeue failed: " + e.getMessage());
        }
    }

    /**
     * GET DEAD LETTER QUEUE STATISTICS
     * Returns statistics about dead letter queue
     */
    public DeadLetterQueueStats getDeadLetterQueueStatistics() {
        try {
            long totalDlqOrders = orderRepository.countDeadLetterQueueOrders();
            
            LocalDateTime past24Hours = LocalDateTime.now().minusHours(24);
            LocalDateTime pastWeek = LocalDateTime.now().minusWeeks(1);
            
            // Get recent DLQ additions (would need additional query methods)
            long dlqLast24Hours = getDlqOrdersCount(past24Hours);
            long dlqLastWeek = getDlqOrdersCount(pastWeek);

            // Get breakdown by error type
            List<Object[]> errorTypeStats = orderRepository.getErrorTypeStatistics();
            List<DlqErrorTypeStats> errorBreakdown = errorTypeStats.stream()
                    .map(row -> DlqErrorTypeStats.builder()
                            .errorType((String) row[0])
                            .count((Long) row[1])
                            .build())
                    .toList();

            return DeadLetterQueueStats.builder()
                    .totalDlqOrders(totalDlqOrders)
                    .dlqLast24Hours(dlqLast24Hours)
                    .dlqLastWeek(dlqLastWeek)
                    .messagesReceived(dlqMessagesReceived.get())
                    .messagesProcessed(dlqMessagesProcessed.get())
                    .messagesFailed(dlqMessagesFailed.get())
                    .errorTypeBreakdown(errorBreakdown)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get dead letter queue statistics: {}", e.getMessage(), e);
            return DeadLetterQueueStats.empty();
        }
    }

    /**
     * CLEANUP OLD DLQ ENTRIES
     * Scheduled task to clean up old dead letter queue entries
     */
    @Scheduled(cron = "${app.dead-letter-queue.cleanup-cron:0 2 * * * *}") // Daily at 2 AM
    public void cleanupOldDlqEntries() {
        try {
            log.info("Starting cleanup of old dead letter queue entries older than {} days", retentionDays);

            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
            
            // Find old DLQ orders (would need additional query method)
            List<Order> oldDlqOrders = findOldDlqOrders(cutoffDate);
            
            int cleanedCount = 0;
            for (Order order : oldDlqOrders) {
                try {
                    purgeDeadLetterQueueEntry(order.getId(), "Automatic cleanup - retention period exceeded");
                    cleanedCount++;
                } catch (Exception e) {
                    log.error("Failed to cleanup DLQ entry for order {}: {}", order.getId(), e.getMessage());
                }
            }

            log.info("Cleanup completed: {} old dead letter queue entries removed", cleanedCount);

        } catch (Exception e) {
            log.error("Failed to cleanup old dead letter queue entries: {}", e.getMessage(), e);
        }
    }

    // Private helper methods

    private DeadLetterQueueEntry createDeadLetterQueueEntry(VideoProcessingMessage message, 
                                                           String topic, int partition, long offset) {
        return DeadLetterQueueEntry.builder()
                .orderId(message.getOrderId())
                .originalMessage(message)
                .failureReason(message.getMetadata().getOrDefault("dlq-reason", "Unknown"))
                .attemptCount(message.getAttemptNumber())
                .topic(topic)
                .partition(partition)
                .offset(offset)
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void storeDlqEntry(DeadLetterQueueEntry dlqEntry) {
        // In a real implementation, this might store to a separate DLQ table
        // For now, we update the order with DLQ information
        log.info("Storing DLQ entry for order {}", dlqEntry.getOrderId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void updateOrderForDeadLetterQueue(Long orderId, DeadLetterQueueEntry dlqEntry) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setIsManuallyFailed(true);
                order.setStatus(OrderStatus.HOLDING);
                order.setFailureReason("Dead Letter Queue: " + dlqEntry.getFailureReason());
                order.setErrorMessage("DLQ - Permanent failure after " + dlqEntry.getAttemptCount() + " attempts");
                order.setUpdatedAt(LocalDateTime.now());
                orderRepository.save(order);
            }
        } catch (Exception e) {
            log.error("Failed to update order {} for DLQ: {}", orderId, e.getMessage(), e);
        }
    }

    private void notifyOperatorsOfDeadLetterQueue(DeadLetterQueueEntry dlqEntry) {
        // Implementation would send notifications to operators
        // Could be email, Slack, dashboard alerts, etc.
        log.warn("OPERATOR NOTIFICATION: Order {} moved to dead letter queue - {}", 
                dlqEntry.getOrderId(), dlqEntry.getFailureReason());
    }

    private long getDlqOrdersCount(LocalDateTime since) {
        // This would need a specialized query - simplified for now
        return orderRepository.countFailedOrdersSince(since);
    }

    private List<Order> findOldDlqOrders(LocalDateTime cutoffDate) {
        // This would need a specialized query - simplified for now
        return orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.HOLDING, cutoffDate);
    }
}

// Supporting classes for DLQ operations

/**
 * Dead letter queue entry representation
 */
@lombok.Builder
@lombok.Data
class DeadLetterQueueEntry {
    private final Long orderId;
    private final VideoProcessingMessage originalMessage;
    private final String failureReason;
    private final Integer attemptCount;
    private final String topic;
    private final Integer partition;
    private final Long offset;
    private final LocalDateTime timestamp;
}

/**
 * DLQ operation result
 */
@lombok.Builder
@lombok.Data
static class DlqOperationResult {
    private final Long orderId;
    private final boolean success;
    private final String message;

    public static DlqOperationResult success(Long orderId, String message) {
        return DlqOperationResult.builder()
                .orderId(orderId)
                .success(true)
                .message(message)
                .build();
    }

    public static DlqOperationResult failed(Long orderId, String message) {
        return DlqOperationResult.builder()
                .orderId(orderId)
                .success(false)
                .message(message)
                .build();
    }
}

/**
 * Dead letter queue statistics
 */
@lombok.Builder
@lombok.Data
static class DeadLetterQueueStats {
    private final long totalDlqOrders;
    private final long dlqLast24Hours;
    private final long dlqLastWeek;
    private final long messagesReceived;
    private final long messagesProcessed;
    private final long messagesFailed;
    private final List<DlqErrorTypeStats> errorTypeBreakdown;

    public static DeadLetterQueueStats empty() {
        return DeadLetterQueueStats.builder()
                .totalDlqOrders(0)
                .dlqLast24Hours(0)
                .dlqLastWeek(0)
                .messagesReceived(0)
                .messagesProcessed(0)
                .messagesFailed(0)
                .errorTypeBreakdown(List.of())
                .build();
    }
}

/**
 * Error type statistics for DLQ
 */
@lombok.Builder
@lombok.Data
static class DlqErrorTypeStats {
    private final String errorType;
    private final Long count;
}