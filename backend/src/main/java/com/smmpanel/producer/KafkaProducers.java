package com.smmpanel.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

/**
 * PRODUCTION-READY Kafka Producers
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaProducers {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Send video processing request
     */
    public void sendVideoProcessingRequest(Long processingId) {
        try {
            log.info("Sending video processing request for ID: {}", processingId);

            ListenableFuture<SendResult<String, Object>> future = 
                kafkaTemplate.send("video-processing", processingId);

            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {
                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    log.info("Successfully sent video processing request: {} with offset: {}", 
                            processingId, result.getRecordMetadata().offset());
                }

                @Override
                public void onFailure(Throwable ex) {
                    log.error("Failed to send video processing request: {}", processingId, ex);
                }
            });

        } catch (Exception e) {
            log.error("Error sending video processing request: {}", e.getMessage(), e);
        }
    }

    /**
     * Send offer assignment request
     */
    public void sendOfferAssignmentRequest(com.smmpanel.dto.binom.OfferAssignmentRequest request) {
        try {
            log.info("Sending offer assignment request for order: {}", request.getOrderId());

            String message = objectMapper.writeValueAsString(request);

            ListenableFuture<SendResult<String, Object>> future = 
                kafkaTemplate.send("offer-assignments", message);

            future.addCallback(new ListenableFutureCallback<SendResult<String, Object>>() {
                @Override
                public void onSuccess(SendResult<String, Object> result) {
                    log.info("Successfully sent offer assignment request for order: {} with offset: {}", 
                            request.getOrderId(), result.getRecordMetadata().offset());
                }

                @Override
                public void onFailure(Throwable ex) {
                    log.error("Failed to send offer assignment request for order: {}", 
                            request.getOrderId(), ex);
                }
            });

        } catch (Exception e) {
            log.error("Error sending offer assignment request: {}", e.getMessage(), e);
        }
    }

    /**
     * Send order state update
     */
    public void sendOrderStateUpdate(Long orderId, String oldStatus, String newStatus) {
        try {
            String message = String.format("{\"orderId\":%d,\"oldStatus\":\"%s\",\"newStatus\":\"%s\",\"timestamp\":\"%s\"}", 
                    orderId, oldStatus, newStatus, java.time.LocalDateTime.now());

            kafkaTemplate.send("order-state-updates", message);
            log.debug("Sent order state update: {}", message);

        } catch (Exception e) {
            log.error("Error sending order state update: {}", e.getMessage(), e);
        }
    }

    /**
     * Send notification
     */
    public void sendNotification(String eventType, Object data) {
        try {
            String message = objectMapper.writeValueAsString(data);
            kafkaTemplate.send("notifications", eventType, message);
            log.debug("Sent notification: {} - {}", eventType, message);

        } catch (Exception e) {
            log.error("Error sending notification: {}", e.getMessage(), e);
        }
    }
}
