package com.smmpanel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MESSAGE PROCESSING SERVICE
 *
 * Provides comprehensive message processing framework:
 * 1. Idempotent message processing with deduplication
 * 2. Graceful error handling with retries and recovery
 * 3. Proper message acknowledgment management
 * 4. Processing metrics and monitoring
 * 5. Transaction management for message processing
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessingService {

    private final TransactionTemplate transactionTemplate;
    @Qualifier("generalAlertService")
    private final AlertService alertService;

    // Message processing state tracking
    private final Set<String> processedMessages = ConcurrentHashMap.newKeySet();
    private final Map<String, LocalDateTime> messageProcessingTimes = new ConcurrentHashMap<>();
    private final Map<String, Integer> messageRetryCount = new ConcurrentHashMap<>();
    
    // Processing metrics
    private final AtomicLong totalProcessedMessages = new AtomicLong(0);
    private final AtomicLong successfullyProcessedMessages = new AtomicLong(0);
    private final AtomicLong failedMessages = new AtomicLong(0);
    private final AtomicLong duplicateMessages = new AtomicLong(0);
    private final AtomicLong retriedMessages = new AtomicLong(0);

    // Configuration
    private static final long MESSAGE_TTL_HOURS = 24;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * Processes a message with comprehensive error handling and idempotency
     */
    public <T> ProcessingResult processMessage(
            T payload,
            String messageId,
            String topic,
            int partition,
            long offset,
            Acknowledgment acknowledgment,
            Function<T, ProcessingResult> processor) {
        
        MessageMetadata metadata = MessageMetadata.builder()
                .messageId(messageId)
                .topic(topic)
                .partition(partition)
                .offset(offset)
                .processingStartTime(LocalDateTime.now())
                .build();

        log.info("Processing message: messageId={}, topic={}, partition={}, offset={}", 
                messageId, topic, partition, offset);

        totalProcessedMessages.incrementAndGet();

        try {
            // Check for duplicate message
            if (isDuplicateMessage(messageId)) {
                log.info("Duplicate message detected: messageId={}", messageId);
                duplicateMessages.incrementAndGet();
                
                // Acknowledge duplicate message to prevent reprocessing
                if (acknowledgment != null) {
                    acknowledgment.acknowledge();
                }
                
                return ProcessingResult.duplicate(messageId, "Message already processed");
            }

            // Process message with transaction support
            ProcessingResult result = processWithTransaction(payload, metadata, processor);
            
            // Handle processing result
            if (result.isSuccess()) {
                handleSuccessfulProcessing(messageId, metadata, acknowledgment);
            } else {
                handleProcessingFailure(messageId, metadata, result, acknowledgment);
            }

            return result;

        } catch (Exception e) {
            log.error("Unexpected error processing message: messageId={}", messageId, e);
            handleUnexpectedError(messageId, metadata, e, acknowledgment);
            return ProcessingResult.error(messageId, "Unexpected processing error", e);
        }
    }

    /**
     * Processes message with retry logic
     */
    public <T> ProcessingResult processMessageWithRetry(
            T payload,
            String messageId,
            String topic,
            int partition,
            long offset,
            Acknowledgment acknowledgment,
            Function<T, ProcessingResult> processor) {

        int retryCount = messageRetryCount.getOrDefault(messageId, 0);
        
        if (retryCount > 0) {
            log.info("Retrying message processing: messageId={}, attempt={}", messageId, retryCount + 1);
            retriedMessages.incrementAndGet();
        }

        ProcessingResult result = processMessage(payload, messageId, topic, partition, offset, acknowledgment, processor);

        if (!result.isSuccess() && retryCount < MAX_RETRY_ATTEMPTS) {
            // Increment retry count for future attempts
            messageRetryCount.put(messageId, retryCount + 1);
            log.warn("Message processing failed, will retry: messageId={}, attempt={}, maxAttempts={}", 
                    messageId, retryCount + 1, MAX_RETRY_ATTEMPTS);
        } else if (!result.isSuccess()) {
            // Max retries reached
            log.error("Message processing failed after max retries: messageId={}, attempts={}", 
                    messageId, MAX_RETRY_ATTEMPTS);
            messageRetryCount.remove(messageId);
            
            // Send to DLQ or alert
            handleMaxRetriesReached(messageId, result);
        } else {
            // Success - clean up retry count
            messageRetryCount.remove(messageId);
        }

        return result;
    }

    /**
     * Safe message processing with comprehensive error handling
     */
    public <T> void safeProcessMessage(
            T payload,
            String messageId,
            String topic,
            int partition, 
            long offset,
            Acknowledgment acknowledgment,
            Consumer<T> processor) {

        try {
            log.debug("Starting safe message processing: messageId={}", messageId);
            
            // Check for duplicates
            if (isDuplicateMessage(messageId)) {
                log.info("Skipping duplicate message: messageId={}", messageId);
                duplicateMessages.incrementAndGet();
                safeAcknowledge(acknowledgment, messageId);
                return;
            }

            // Process with full error handling
            transactionTemplate.execute(status -> {
                try {
                    processor.accept(payload);
                    markMessageAsProcessed(messageId);
                    log.debug("Message processed successfully: messageId={}", messageId);
                    return null;
                } catch (Exception e) {
                    log.error("Error in safe message processing: messageId={}", messageId, e);
                    status.setRollbackOnly();
                    throw new RuntimeException("Processing failed", e);
                }
            });

            successfullyProcessedMessages.incrementAndGet();
            safeAcknowledge(acknowledgment, messageId);

        } catch (Exception e) {
            log.error("Safe message processing failed: messageId={}", messageId, e);
            failedMessages.incrementAndGet();
            
            // Don't acknowledge failed messages to allow retry
            handleProcessingException(messageId, e);
        }
    }

    /**
     * Checks if message is duplicate based on message ID
     */
    public boolean isDuplicateMessage(String messageId) {
        return processedMessages.contains(messageId);
    }

    /**
     * Marks message as processed for deduplication
     */
    public void markMessageAsProcessed(String messageId) {
        processedMessages.add(messageId);
        messageProcessingTimes.put(messageId, LocalDateTime.now());
        
        log.debug("Message marked as processed: messageId={}", messageId);
    }

    /**
     * Safe message acknowledgment
     */
    public void safeAcknowledge(Acknowledgment acknowledgment, String messageId) {
        if (acknowledgment != null) {
            try {
                acknowledgment.acknowledge();
                log.debug("Message acknowledged: messageId={}", messageId);
            } catch (Exception e) {
                log.error("Failed to acknowledge message: messageId={}", messageId, e);
                // Don't throw - acknowledgment failure shouldn't fail processing
            }
        }
    }

    /**
     * Gets processing metrics
     */
    public MessageProcessingMetrics getProcessingMetrics() {
        return MessageProcessingMetrics.builder()
                .totalProcessed(totalProcessedMessages.get())
                .successfullyProcessed(successfullyProcessedMessages.get())
                .failed(failedMessages.get())
                .duplicates(duplicateMessages.get())
                .retried(retriedMessages.get())
                .successRate(calculateSuccessRate())
                .duplicateRate(calculateDuplicateRate())
                .currentProcessedMessagesCount(processedMessages.size())
                .pendingRetries(messageRetryCount.size())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Cleans up old processed message tracking data
     */
    public void cleanupProcessedMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(MESSAGE_TTL_HOURS);
        int removedCount = 0;
        
        messageProcessingTimes.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                processedMessages.remove(entry.getKey());
                return true;
            }
            return false;
        });
        
        if (removedCount > 0) {
            log.info("Cleaned up {} old processed message records", removedCount);
        }
    }

    /**
     * Processes message within transaction
     */
    private <T> ProcessingResult processWithTransaction(
            T payload,
            MessageMetadata metadata,
            Function<T, ProcessingResult> processor) {
        
        return transactionTemplate.execute(status -> {
            try {
                ProcessingResult result = processor.apply(payload);
                
                if (result.isSuccess()) {
                    markMessageAsProcessed(metadata.getMessageId());
                } else {
                    status.setRollbackOnly();
                }
                
                return result;
                
            } catch (Exception e) {
                log.error("Transaction processing failed: messageId={}", metadata.getMessageId(), e);
                status.setRollbackOnly();
                return ProcessingResult.error(metadata.getMessageId(), "Transaction processing failed", e);
            }
        });
    }

    /**
     * Handles successful message processing
     */
    private void handleSuccessfulProcessing(String messageId, MessageMetadata metadata, Acknowledgment acknowledgment) {
        successfullyProcessedMessages.incrementAndGet();
        safeAcknowledge(acknowledgment, messageId);
        
        log.info("Message processed successfully: messageId={}, processingTime={}ms", 
                messageId, 
                java.time.Duration.between(metadata.getProcessingStartTime(), LocalDateTime.now()).toMillis());
    }

    /**
     * Handles processing failure
     */
    private void handleProcessingFailure(String messageId, MessageMetadata metadata, 
                                       ProcessingResult result, Acknowledgment acknowledgment) {
        failedMessages.incrementAndGet();
        
        log.error("Message processing failed: messageId={}, error={}", messageId, result.getErrorMessage());
        
        // Don't acknowledge failed messages to allow retry/DLQ handling
        if (result.isFatal()) {
            log.error("Fatal error processing message: messageId={}, sending alert", messageId);
            sendProcessingAlert(messageId, result);
        }
    }

    /**
     * Handles unexpected errors
     */
    private void handleUnexpectedError(String messageId, MessageMetadata metadata, 
                                     Exception e, Acknowledgment acknowledgment) {
        failedMessages.incrementAndGet();
        
        log.error("Unexpected error processing message: messageId={}", messageId, e);
        
        // Send critical alert for unexpected errors
        try {
            alertService.sendCriticalAlert(
                "Unexpected Message Processing Error",
                String.format("MessageId: %s, Error: %s", messageId, e.getMessage())
            );
        } catch (Exception alertException) {
            log.error("Failed to send alert for unexpected error", alertException);
        }
    }

    /**
     * Handles max retries reached
     */
    private void handleMaxRetriesReached(String messageId, ProcessingResult result) {
        try {
            alertService.sendAlert(
                "Message Processing Max Retries Reached",
                String.format("MessageId: %s, Error: %s", messageId, result.getErrorMessage())
            );
        } catch (Exception e) {
            log.error("Failed to send max retries alert", e);
        }
    }

    /**
     * Handles processing exceptions
     */
    private void handleProcessingException(String messageId, Exception e) {
        log.error("Processing exception for message: messageId={}", messageId, e);
        
        // Add to retry count if not at max
        int retryCount = messageRetryCount.getOrDefault(messageId, 0);
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            messageRetryCount.put(messageId, retryCount + 1);
        }
    }

    /**
     * Sends processing alert
     */
    private void sendProcessingAlert(String messageId, ProcessingResult result) {
        try {
            alertService.sendAlert(
                "Message Processing Alert",
                String.format("MessageId: %s, Error: %s", messageId, result.getErrorMessage())
            );
        } catch (Exception e) {
            log.error("Failed to send processing alert", e);
        }
    }

    /**
     * Calculates success rate
     */
    private double calculateSuccessRate() {
        long total = totalProcessedMessages.get();
        if (total == 0) return 100.0;
        return (successfullyProcessedMessages.get() * 100.0) / total;
    }

    /**
     * Calculates duplicate rate
     */
    private double calculateDuplicateRate() {
        long total = totalProcessedMessages.get();
        if (total == 0) return 0.0;
        return (duplicateMessages.get() * 100.0) / total;
    }

    /**
     * Message metadata holder
     */
    @lombok.Builder
    @lombok.Data
    public static class MessageMetadata {
        private final String messageId;
        private final String topic;
        private final int partition;
        private final long offset;
        private final LocalDateTime processingStartTime;
    }

    /**
     * Processing result holder
     */
    @lombok.Builder
    @lombok.Data
    public static class ProcessingResult {
        private final String messageId;
        private final boolean success;
        private final boolean duplicate;
        private final boolean fatal;
        private final String errorMessage;
        private final Exception exception;
        private final LocalDateTime timestamp;

        public static ProcessingResult success(String messageId) {
            return ProcessingResult.builder()
                    .messageId(messageId)
                    .success(true)
                    .duplicate(false)
                    .fatal(false)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        public static ProcessingResult duplicate(String messageId, String message) {
            return ProcessingResult.builder()
                    .messageId(messageId)
                    .success(true)
                    .duplicate(true)
                    .fatal(false)
                    .errorMessage(message)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        public static ProcessingResult error(String messageId, String errorMessage, Exception e) {
            return ProcessingResult.builder()
                    .messageId(messageId)
                    .success(false)
                    .duplicate(false)
                    .fatal(false)
                    .errorMessage(errorMessage)
                    .exception(e)
                    .timestamp(LocalDateTime.now())
                    .build();
        }

        public static ProcessingResult fatalError(String messageId, String errorMessage, Exception e) {
            return ProcessingResult.builder()
                    .messageId(messageId)
                    .success(false)
                    .duplicate(false)
                    .fatal(true)
                    .errorMessage(errorMessage)
                    .exception(e)
                    .timestamp(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Processing metrics holder
     */
    @lombok.Builder
    @lombok.Data
    public static class MessageProcessingMetrics {
        private final long totalProcessed;
        private final long successfullyProcessed;
        private final long failed;
        private final long duplicates;
        private final long retried;
        private final double successRate;
        private final double duplicateRate;
        private final int currentProcessedMessagesCount;
        private final int pendingRetries;
        private final LocalDateTime timestamp;
    }
}