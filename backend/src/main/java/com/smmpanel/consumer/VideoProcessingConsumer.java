package com.smmpanel.consumer;

import com.smmpanel.service.VideoProcessingService;
import com.smmpanel.service.order.OrderProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * PRODUCTION-READY Kafka Consumers for async processing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessingConsumer {

    private final VideoProcessingService videoProcessingService;
    private final OrderProcessingService orderProcessingService;

    /**
     * Process video processing requests
     */
    @KafkaListener(
        topics = "smm.video.processing",
        groupId = "smm-video-processing-group",
        containerFactory = "highThroughputKafkaListenerContainerFactory"
    )
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 5000))
    public void handleVideoProcessing(
            @Payload Long processingId,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        try {
            log.info("Processing video processing request: {} from topic: {}, partition: {}, offset: {}", 
                    processingId, topic, partition, offset);

            // Process video asynchronously
            videoProcessingService.processVideo(processingId);

            // Acknowledge successful processing
            acknowledgment.acknowledge();
            
            log.info("Successfully processed video processing: {}", processingId);

        } catch (Exception e) {
            log.error("Failed to process video processing {}: {}", processingId, e.getMessage(), e);
            
            // Don't acknowledge on failure - message will be retried
            throw e;
        }
    }

    /**
     * Handle order state updates
     */
    @KafkaListener(
        topics = "smm.order.state.updates",
        groupId = "order-state-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOrderStateUpdate(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment acknowledgment) {
        
        try {
            log.debug("Received order state update: {} from topic: {}", message, topic);

            // Parse message and handle state update
            // This is for future state management if needed
            
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process order state update: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Handle notification events
     */
    @KafkaListener(
        topics = "smm.notifications",
        groupId = "notifications-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleNotification(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment acknowledgment) {
        
        try {
            log.debug("Received notification: {} from topic: {}", message, topic);

            // Process notification
            // This could integrate with external notification services
            
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process notification: {}", e.getMessage(), e);
            throw e;
        }
    }
}
