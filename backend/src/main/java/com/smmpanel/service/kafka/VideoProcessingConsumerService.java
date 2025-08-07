package com.smmpanel.service.kafka;

import com.smmpanel.dto.kafka.VideoProcessingMessage;
import com.smmpanel.service.YouTubeProcessingService;
import com.smmpanel.service.OrderStateManagementService;
import com.smmpanel.service.ErrorRecoveryService;
import com.smmpanel.service.DeadLetterQueueService;
import com.smmpanel.service.monitoring.MemoryMonitoringService;
import com.smmpanel.config.KafkaConsumerMemoryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/**
 * KAFKA CONSUMER SERVICE: Video Processing Message Queue
 * 
 * Consumes and processes video processing messages from Kafka with:
 * 1. Reliable message processing with manual acknowledgment
 * 2. Error handling and retry logic
 * 3. Performance monitoring and metrics
 * 4. Dead letter queue handling for failed messages
 * 5. Memory usage monitoring and management
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoProcessingConsumerService {

    private final YouTubeProcessingService youTubeProcessingService;
    private final VideoProcessingProducerService producerService;
    private final OrderStateManagementService orderStateManagementService;
    private final ErrorRecoveryService errorRecoveryService;
    private final DeadLetterQueueService deadLetterQueueService;
    private final MemoryMonitoringService memoryMonitoringService;
    private final KafkaConsumerMemoryConfig memoryConfig;

    @Value("${app.kafka.video-processing.consumer.processing-timeout:300000}")
    private long processingTimeoutMs;

    @Value("${app.kafka.video-processing.consumer.retry-delay-seconds:30}")
    private int retryDelaySeconds;

    private final TransactionTemplate transactionTemplate;

    // Metrics tracking
    private final AtomicLong messagesReceived = new AtomicLong(0);
    
    @PostConstruct
    public void initialize() {
        // Configure memory settings from properties
        System.setProperty("kafka.consumer.memory.max", memoryConfig.getHeap().getMaxSize());
        System.setProperty("kafka.consumer.memory.initial", memoryConfig.getHeap().getInitialSize());
        
        // Initialize memory monitoring
        memoryMonitoringService.initialize();
        
        log.info("Initialized Kafka consumer with memory settings: {}", 
                memoryMonitoringService.getMemoryUsageSummary().getFormattedSummary());
    }
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private final AtomicLong messagesRetried = new AtomicLong(0);
    private final AtomicLong totalProcessingTimeMs = new AtomicLong(0);

    /**
     * MAIN CONSUMER: Process video processing messages from queue
     * Configured for manual acknowledgment and reliable processing
     */
    private final KafkaMonitoringService monitoringService;

    @PostConstruct
    public void initialize() {
        // Initialize monitoring for this consumer group
        monitoringService.initializeConsumerGroupMonitoring(
            "${app.kafka.video-processing.consumer.group-id:video-processing-group}",
            "${app.kafka.video-processing.topic:video.processing.queue}"
        );
    }

    @KafkaListener(
        topics = "${app.kafka.video-processing.topic:video.processing.queue}",
        groupId = "${app.kafka.video-processing.consumer.group-id:video-processing-group}",
        containerFactory = "videoProcessingKafkaListenerContainerFactory"
    )
    public void processVideoMessage(
            @Payload VideoProcessingMessage message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header("kafka_receivedMessageKey") String key,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        final String groupId = "${app.kafka.video-processing.consumer.group-id:video-processing-group}";
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // Check idempotency first
        if (messageIdempotencyService.isDuplicate(message.getMessageId(), message.getTimestamp())) {
            log.info("Skipping duplicate message: messageId={}, orderId={}", 
                    message.getMessageId(), message.getOrderId());
            acknowledgment.acknowledge();
            return;
        }
        
        try {
            messagesReceived.incrementAndGet();
            
            log.info("Received video processing message: {} from partition={}, offset={}", 
                    message.getSummary(), partition, offset);

            // Validate message
            if (!isValidMessage(message)) {
                log.error("Invalid message received: {}", message.getSummary());
                acknowledgment.acknowledge(); // Acknowledge to avoid reprocessing
                messagesFailed.incrementAndGet();
                return;
            }

            // Check if message should be processed now (for delayed messages)
            if (!message.shouldProcessImmediately()) {
                long delaySeconds = message.getProcessingDelaySeconds();
                log.info("Message scheduled for future processing: orderId={}, delay={}s", 
                        message.getOrderId(), delaySeconds);
                
                // Requeue with delay (simplified - in production might use delay queue)
                scheduleMessageForLater(message, delaySeconds);
                acknowledgment.acknowledge();
                return;
            }

            // Process the message
            boolean success = processMessage(message);
            
            if (success) {
                log.info("Successfully processed video message: orderId={}, processing time={}ms", 
                        message.getOrderId(), stopWatch.getTotalTimeMillis());
                
                messagesProcessed.incrementAndGet();
                acknowledgment.acknowledge();
                
            } else {
                log.warn("Failed to process video message: orderId={}, attempt={}/{}", 
                        message.getOrderId(), message.getAttemptNumber(), message.getMaxAttempts());
                
                handleProcessingFailure(message, acknowledgment);
            }

        } catch (Exception e) {
            log.error("Unexpected error processing video message: orderId={}, error={}", 
                    message.getOrderId(), e.getMessage(), e);
            
            // Record error in monitoring
            monitoringService.recordError(groupId);
            
            handleProcessingFailure(message, acknowledgment);
            
        } finally {
            stopWatch.stop();
            // Record processing time
            monitoringService.recordProcessingTime(groupId, stopWatch.getTotalTimeMillis());
            totalProcessingTimeMs.addAndGet(stopWatch.getTotalTimeMillis());
        }
    }

    /**
     * PROCESS MESSAGE: Execute video processing for the message
     * WITH STATE MANAGEMENT INTEGRATION
     */
    private boolean processMessage(VideoProcessingMessage message) {
        final String operationId = UUID.randomUUID().toString();
        
        try {
            log.info("Processing video order: orderId={}, videoId={}, priority={}, operationId={}", 
                    message.getOrderId(), message.getVideoId(), message.getPriority(), operationId);

            // Update processing status to indicate message consumption
            orderStateManagementService.updateProcessingStatus(message.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.VIDEO_ANALYSIS,
                    String.format("Kafka consumer processing message (attempt %d/%d)", 
                            message.getAttemptNumber(), message.getMaxAttempts()));

            // Add message metadata to track processing
            if (message.getMetadata() != null) {
                message.addMetadata("kafka-processing-start", LocalDateTime.now().toString());
                message.addMetadata("consumer-attempt", message.getAttemptNumber().toString());
            }

            // Delegate to YouTube processing service for actual processing
            youTubeProcessingService.processYouTubeOrder(message.getOrderId());
            
            log.info("Video processing completed successfully: orderId={}", message.getOrderId());
            return true;

        } catch (Exception e) {
            log.error("Video processing failed: orderId={}, error={}", 
                    message.getOrderId(), e.getMessage(), e);
            
            // Record error for recovery system
            errorRecoveryService.recordErrorAndScheduleRetry(
                    message.getOrderId(),
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    "VIDEO_PROCESSING",
                    e
            );
            
            // Update state management to reflect processing failure
            orderStateManagementService.updateProcessingStatus(message.getOrderId(),
                    OrderStateManagementService.ProcessingPhase.VIDEO_ANALYSIS,
                    String.format("Processing failed: %s (will retry if attempts remain)", e.getMessage()));
            
            return false;
        }
    }

    /**
     * HANDLE PROCESSING FAILURE: Retry or send to dead letter queue
     */
    private void handleProcessingFailure(VideoProcessingMessage message, Acknowledgment acknowledgment) {
        messagesFailed.incrementAndGet();
        
        try {
            // Record failure metrics
            errorRecoveryService.recordFailure(message.getOrderId(), "video-processing", 
                    String.format("Processing failed on attempt %d/%d", 
                    message.getAttemptNumber(), message.getMaxAttempts()));

            if (!message.hasExceededMaxAttempts()) {
                // Determine if error is retryable
                if (errorRecoveryService.isRetryableError(message.getOrderId())) {
                    // Retry the message with exponential backoff
                    log.info("Retrying video processing message: orderId={}, attempt={}/{}", 
                            message.getOrderId(), message.getAttemptNumber() + 1, message.getMaxAttempts());
                
                    VideoProcessingMessage retryMessage = message.createRetryMessage();
                    retryMessage.addMetadata("retry-reason", "processing-failed");
                    retryMessage.addMetadata("retry-timestamp", LocalDateTime.now().toString());
                    
                    // Send retry message with delay
                    producerService.sendRetryMessage(message);
                    messagesRetried.incrementAndGet();
                    
                    // Acknowledge original message to avoid duplicate processing
                    acknowledgment.acknowledge();
                } else {
                    // Error is not retryable - send directly to DLQ
                    log.warn("Non-retryable error for message: orderId={}, sending to DLQ", message.getOrderId());
                    acknowledgment.acknowledge();
                }
            } else {
                // Max attempts exceeded - send to dead letter queue with enhanced metadata
                log.error("Max retry attempts exceeded for message: orderId={}, sending to DLQ", 
                        message.getOrderId());
                
                // Gather error context
                Map<String, Object> errorContext = new HashMap<>();
                errorContext.put("failedAttempts", message.getAttemptNumber());
                errorContext.put("firstFailureTimestamp", message.getTimestamp());
                errorContext.put("lastProcessingTimestamp", LocalDateTime.now());
                errorContext.put("errorHistory", errorRecoveryService.getErrorHistory(message.getOrderId()));
                
                // Send to DLQ with enhanced metadata
                deadLetterQueueService.sendToDeadLetterQueue(
                    message, 
                    "Max retry attempts exceeded - Processing permanently failed",
                    errorContext
                );
                
                // Update order status and notify relevant parties
                orderStateManagementService.markOrderAsFailed(
                    message.getOrderId(),
                    "Processing failed permanently after " + message.getAttemptNumber() + " attempts"
                );
                
                acknowledgment.acknowledge();
            }
            
        } catch (Exception e) {
            log.error("Error handling processing failure: orderId={}, error={}", 
                    message.getOrderId(), e.getMessage(), e);
            
            // Acknowledge to prevent infinite reprocessing
            acknowledgment.acknowledge();
        }
    }

    /**
     * VALIDATE MESSAGE: Check if message has required fields and is processable
     */
    private boolean isValidMessage(VideoProcessingMessage message) {
        if (message == null) {
            log.error("Received null message");
            return false;
        }

        // Check idempotency fields
        if (message.getMessageId() == null || message.getMessageId().trim().isEmpty()) {
            log.error("Missing messageId for idempotency");
            return false;
        }

        if (message.getTimestamp() == null) {
            log.error("Missing timestamp for idempotency");
            return false;
        }

        // Check business fields
        if (message.getOrderId() == null || message.getOrderId() <= 0) {
            log.error("Invalid order ID in message: {}", message.getOrderId());
            return false;
        }

        if (message.getVideoId() == null || message.getVideoId().trim().isEmpty()) {
            log.error("Invalid video ID in message: orderId={}", message.getOrderId());
            return false;
        }

        if (message.getOriginalUrl() == null || message.getOriginalUrl().trim().isEmpty()) {
            log.error("Invalid original URL in message: orderId={}", message.getOrderId());
            return false;
        }

        if (message.getTargetQuantity() == null || message.getTargetQuantity() <= 0) {
            log.error("Invalid target quantity in message: orderId={}, quantity={}", 
                    message.getOrderId(), message.getTargetQuantity());
            return false;
        }

        return true;
    }

    /**
     * SCHEDULE MESSAGE FOR LATER: Handle delayed processing (simplified implementation)
     */
    private void scheduleMessageForLater(VideoProcessingMessage message, long delaySeconds) {
        // In a production system, this might use a delay queue or scheduler
        // For now, we'll use a simple approach
        log.info("Scheduling message for later processing: orderId={}, delay={}s", 
                message.getOrderId(), delaySeconds);
        
        // This could be enhanced with a proper delay queue implementation
        java.util.concurrent.CompletableFuture.delayedExecutor(
                delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> {
                    try {
                        producerService.sendVideoProcessingMessage(message);
                        log.info("Requeued delayed message: orderId={}", message.getOrderId());
                    } catch (Exception e) {
                        log.error("Failed to requeue delayed message: orderId={}, error={}", 
                                message.getOrderId(), e.getMessage(), e);
                    }
                });
    }

    /**
     * SEND TO DEAD LETTER QUEUE: Handle messages that can't be processed
     * DEPRECATED: Use DeadLetterQueueService.sendToDeadLetterQueue() instead
     */
    @Deprecated
    private void sendToDeadLetterQueue(VideoProcessingMessage message) {
        deadLetterQueueService.sendToDeadLetterQueue(message, "Consumer processing failure");
    }

    /**
     * GET METRICS: Return consumer performance metrics
     */
    public ConsumerMetrics getMetrics() {
        long received = messagesReceived.get();
        long processed = messagesProcessed.get();
        long failed = messagesFailed.get();
        long retried = messagesRetried.get();
        
        double successRate = received > 0 ? (double) processed / received * 100.0 : 100.0;
        double avgProcessingTime = processed > 0 ? (double) totalProcessingTimeMs.get() / processed : 0.0;
        
        return ConsumerMetrics.builder()
                .messagesReceived(received)
                .messagesProcessed(processed)
                .messagesFailed(failed)
                .messagesRetried(retried)
                .successRate(successRate)
                .averageProcessingTimeMs(avgProcessingTime)
                .build();
    }

    /**
     * RESET METRICS: Reset all counters (useful for testing)
     */
    public void resetMetrics() {
        messagesReceived.set(0);
        messagesProcessed.set(0);
        messagesFailed.set(0);
        messagesRetried.set(0);
        totalProcessingTimeMs.set(0);
        log.info("Consumer metrics reset");
    }

    /**
     * Consumer metrics data structure
     */
    @lombok.Builder
    @lombok.Data
    public static class ConsumerMetrics {
        private long messagesReceived;
        private long messagesProcessed;
        private long messagesFailed;
        private long messagesRetried;
        private double successRate;
        private double averageProcessingTimeMs;
        
        public String getSummary() {
            return String.format("Consumer[received=%d, processed=%d, failed=%d, retried=%d, rate=%.2f%%, avgTime=%.2fms]",
                    messagesReceived, messagesProcessed, messagesFailed, messagesRetried, successRate, averageProcessingTimeMs);
        }
    }
}