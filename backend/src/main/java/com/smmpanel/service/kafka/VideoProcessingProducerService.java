package com.smmpanel.service.kafka;

import com.smmpanel.dto.kafka.VideoProcessingMessage;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * KAFKA PRODUCER SERVICE: Video Processing Message Queue
 *
 * <p>Handles sending video processing messages to Kafka queue with: 1. Reliable message delivery
 * with callbacks 2. Message routing and partitioning 3. Error handling and retry logic 4.
 * Performance monitoring and metrics
 */
@Slf4j
@Service
public class VideoProcessingProducerService {

    private final KafkaTemplate<String, VideoProcessingMessage> kafkaTemplate;

    public VideoProcessingProducerService(
            @org.springframework.beans.factory.annotation.Qualifier("videoProcessingKafkaTemplate") KafkaTemplate<String, VideoProcessingMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Value("${app.kafka.video-processing.topic:smm.video.processing}")
    private String videoProcessingTopic;

    @Value("${app.kafka.video-processing.producer.timeout:30000}")
    private long sendTimeoutMs;

    // Metrics tracking
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesSucceeded = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);

    /**
     * SEND MESSAGE: Send video processing message to queue Uses order ID as routing key for
     * consistent partitioning
     */
    public CompletableFuture<SendResult<String, VideoProcessingMessage>> sendVideoProcessingMessage(
            VideoProcessingMessage message) {

        try {
            // Create message with headers for better tracking
            Message<VideoProcessingMessage> kafkaMessage =
                    MessageBuilder.withPayload(message)
                            .setHeader(KafkaHeaders.TOPIC, videoProcessingTopic)
                            .setHeader(KafkaHeaders.KEY, message.getRoutingKey())
                            .setHeader("message-type", "video-processing")
                            .setHeader("priority", message.getPriority().name())
                            .setHeader("attempt", message.getAttemptNumber())
                            .setHeader("created-at", message.getCreatedAt().toString())
                            .setHeader("order-id", message.getOrderId().toString())
                            .build();

            log.info("Sending video processing message to Kafka: {}", message.getSummary());

            // Only increment messagesSent after successfully creating the message and before
            // sending
            messagesSent.incrementAndGet();

            CompletableFuture<SendResult<String, VideoProcessingMessage>> future =
                    kafkaTemplate.send(kafkaMessage);

            // Add success/failure callbacks
            future.whenComplete(
                    (result, ex) -> {
                        if (ex == null) {
                            messagesSucceeded.incrementAndGet();
                            log.info(
                                    "Video processing message sent successfully: orderId={},"
                                            + " partition={}, offset={}",
                                    message.getOrderId(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        } else {
                            messagesFailed.incrementAndGet();
                            log.error(
                                    "Failed to send video processing message: orderId={}, error={}",
                                    message.getOrderId(),
                                    ex.getMessage(),
                                    ex);
                        }
                    });

            return future;

        } catch (Exception e) {
            log.error(
                    "Error creating Kafka message for order {}: {}",
                    message.getOrderId(),
                    e.getMessage(),
                    e);

            // Return failed future - failure will be counted in the callback
            CompletableFuture<SendResult<String, VideoProcessingMessage>> failedFuture =
                    new CompletableFuture<>();
            failedFuture.completeExceptionally(e);

            // Add the same callback for consistency
            failedFuture.whenComplete(
                    (result, ex) -> {
                        if (ex != null) {
                            messagesFailed.incrementAndGet();
                        }
                    });

            return failedFuture;
        }
    }

    /** SEND STANDARD MESSAGE: Convenience method for standard processing */
    public CompletableFuture<SendResult<String, VideoProcessingMessage>>
            sendStandardProcessingMessage(
                    Long orderId,
                    String videoId,
                    String originalUrl,
                    Integer targetQuantity,
                    Long userId) {

        VideoProcessingMessage message =
                VideoProcessingMessage.createStandardMessage(
                        orderId, videoId, originalUrl, targetQuantity, userId);

        return sendVideoProcessingMessage(message);
    }

    /** SEND HIGH PRIORITY MESSAGE: For premium orders requiring immediate processing */
    public CompletableFuture<SendResult<String, VideoProcessingMessage>> sendHighPriorityMessage(
            Long orderId, String videoId, String originalUrl, Integer targetQuantity, Long userId) {

        VideoProcessingMessage message =
                VideoProcessingMessage.createHighPriorityMessage(
                        orderId, videoId, originalUrl, targetQuantity, userId);

        return sendVideoProcessingMessage(message);
    }

    /** SEND RETRY MESSAGE: For failed processing attempts */
    public CompletableFuture<SendResult<String, VideoProcessingMessage>> sendRetryMessage(
            VideoProcessingMessage originalMessage) {

        if (originalMessage.hasExceededMaxAttempts()) {
            log.warn(
                    "Message has exceeded max attempts, not retrying: {}",
                    originalMessage.getSummary());
            CompletableFuture<SendResult<String, VideoProcessingMessage>> failedFuture =
                    new CompletableFuture<>();
            failedFuture.completeExceptionally(
                    new IllegalStateException(
                            "Max retry attempts exceeded for order: "
                                    + originalMessage.getOrderId()));
            return failedFuture;
        }

        VideoProcessingMessage retryMessage = originalMessage.createRetryMessage();
        retryMessage.addMetadata("retry-reason", "processing-failed");
        retryMessage.addMetadata("original-attempt", originalMessage.getAttemptNumber().toString());

        log.info(
                "Sending retry message for order {}: attempt {}/{}",
                retryMessage.getOrderId(),
                retryMessage.getAttemptNumber(),
                retryMessage.getMaxAttempts());

        return sendVideoProcessingMessage(retryMessage);
    }

    /** SEND DELAYED MESSAGE: For scheduled processing */
    public CompletableFuture<SendResult<String, VideoProcessingMessage>> sendDelayedMessage(
            Long orderId,
            String videoId,
            String originalUrl,
            Integer targetQuantity,
            Long userId,
            LocalDateTime scheduleAt) {

        VideoProcessingMessage message =
                VideoProcessingMessage.createStandardMessage(
                        orderId, videoId, originalUrl, targetQuantity, userId);
        message.setScheduleAt(scheduleAt);
        message.addMetadata("scheduled", "true");
        message.addMetadata("schedule-time", scheduleAt.toString());

        log.info(
                "Sending delayed processing message for order {}: scheduled for {}",
                orderId,
                scheduleAt);

        return sendVideoProcessingMessage(message);
    }

    /** SEND BATCH MESSAGES: Send multiple messages efficiently */
    public CompletableFuture<Void> sendBatchMessages(
            java.util.List<VideoProcessingMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Sending batch of {} video processing messages", messages.size());

        // Send all messages and collect futures
        java.util.List<CompletableFuture<SendResult<String, VideoProcessingMessage>>> futures =
                messages.stream()
                        .map(this::sendVideoProcessingMessage)
                        .collect(java.util.stream.Collectors.toList());

        // Return when all are complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete(
                        (result, ex) -> {
                            if (ex == null) {
                                log.info(
                                        "Batch send completed successfully: {} messages",
                                        messages.size());
                            } else {
                                log.error("Batch send failed: {}", ex.getMessage(), ex);
                            }
                        });
    }

    /** SEND WITH CUSTOM CONFIG: Send message with additional processing configuration */
    public CompletableFuture<SendResult<String, VideoProcessingMessage>> sendWithConfig(
            Long orderId,
            String videoId,
            String originalUrl,
            Integer targetQuantity,
            Long userId,
            java.util.Map<String, Object> processingConfig) {

        VideoProcessingMessage message =
                VideoProcessingMessage.createStandardMessage(
                        orderId, videoId, originalUrl, targetQuantity, userId);

        if (processingConfig != null) {
            processingConfig.forEach(message::addProcessingConfig);
        }

        return sendVideoProcessingMessage(message);
    }

    /** GET METRICS: Return producer performance metrics */
    public ProducerMetrics getMetrics() {
        return ProducerMetrics.builder()
                .messagesSent(messagesSent.get())
                .messagesSucceeded(messagesSucceeded.get())
                .messagesFailed(messagesFailed.get())
                .successRate(calculateSuccessRate())
                .topic(videoProcessingTopic)
                .build();
    }

    /** RESET METRICS: Reset all counters (useful for testing) */
    public void resetMetrics() {
        messagesSent.set(0);
        messagesSucceeded.set(0);
        messagesFailed.set(0);
        log.info("Producer metrics reset");
    }

    /** HEALTH CHECK: Verify Kafka connectivity */
    public boolean isHealthy() {
        try {
            // Test connectivity by getting partition metadata
            kafkaTemplate.partitionsFor(videoProcessingTopic);
            return true;
        } catch (Exception e) {
            log.warn("Kafka health check failed: {}", e.getMessage());
            return false;
        }
    }

    private double calculateSuccessRate() {
        long sent = messagesSent.get();
        if (sent == 0) {
            return 100.0;
        }
        return (double) messagesSucceeded.get() / sent * 100.0;
    }

    /** Producer metrics data structure */
    @lombok.Builder
    @lombok.Data
    public static class ProducerMetrics {
        private long messagesSent;
        private long messagesSucceeded;
        private long messagesFailed;
        private double successRate;
        private String topic;

        public String getSummary() {
            return String.format(
                    "Producer[sent=%d, success=%d, failed=%d, rate=%.2f%%, topic=%s]",
                    messagesSent, messagesSucceeded, messagesFailed, successRate, topic);
        }
    }
}
