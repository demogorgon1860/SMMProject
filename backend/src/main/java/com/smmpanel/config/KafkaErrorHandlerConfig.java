package com.smmpanel.config;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

/** Comprehensive Kafka Error Handler with DLQ support */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaErrorHandlerConfig {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Create a production-ready error handler with DLQ support */
    public DefaultErrorHandler createErrorHandler() {
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(
                        (consumerRecord, exception) -> {
                            log.error(
                                    "Message processing failed after all retries. Topic: {},"
                                            + " Partition: {}, Offset: {}, Key: {}, Error: {}",
                                    consumerRecord.topic(),
                                    consumerRecord.partition(),
                                    consumerRecord.offset(),
                                    consumerRecord.key(),
                                    exception.getMessage());

                            // Send to DLQ
                            sendToDlq(consumerRecord, exception);
                        },
                        new FixedBackOff(1000L, 3L) // 1 second delay, 3 retries
                        );

        // Configure which exceptions should not be retried
        errorHandler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                java.lang.NumberFormatException.class,
                java.lang.ClassCastException.class,
                org.springframework.kafka.support.serializer.DeserializationException.class);

        return errorHandler;
    }

    /** Send failed message to appropriate DLQ topic */
    private void sendToDlq(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
        try {
            String dlqTopic = getDlqTopic(consumerRecord.topic());
            if (dlqTopic != null) {
                // Create DLQ message with metadata
                Map<String, Object> dlqMessage =
                        Map.of(
                                "originalTopic", consumerRecord.topic(),
                                "originalPartition", consumerRecord.partition(),
                                "originalOffset", consumerRecord.offset(),
                                "originalKey",
                                        consumerRecord.key() != null
                                                ? consumerRecord.key().toString()
                                                : "unknown",
                                "originalValue", consumerRecord.value(),
                                "errorMessage", exception.getMessage(),
                                "errorClass", exception.getClass().getSimpleName(),
                                "timestamp", java.time.LocalDateTime.now().toString());

                kafkaTemplate.send(
                        dlqTopic,
                        consumerRecord.key() != null ? consumerRecord.key().toString() : "unknown",
                        dlqMessage);

                log.info("Message sent to DLQ: {} for topic: {}", dlqTopic, consumerRecord.topic());
            } else {
                log.warn("No DLQ topic configured for: {}", consumerRecord.topic());
            }
        } catch (Exception e) {
            log.error("Failed to send message to DLQ: {}", e.getMessage(), e);
        }
    }

    /** Map original topic to DLQ topic */
    private String getDlqTopic(String originalTopic) {
        return switch (originalTopic) {
            case "smm.order.processing" -> "smm.order.processing.dlq";
            case "smm.video.processing" -> "smm.video.processing.dlq";
            case "smm.youtube.processing" -> "smm.youtube.processing.dlq";
            case "smm.offer.assignments" -> "smm.offer.assignments.dlq";
            case "smm.offer.assignment.events" -> "smm.offer.assignments.dlq";
            case "smm.order.refund" -> "smm.order.refund.dlq";
            case "smm.binom.campaign.creation" -> "smm.binom.campaign.creation.dlq";
            case "smm.video.processing.retry" -> "smm.video.processing.retry.dlq";
            case "smm.order.state.updates" -> "smm.order.state.updates.dlq";
            case "smm.notifications" -> "smm.notifications.dlq";
            default -> null;
        };
    }

    /** Handle container errors */
    public void handleContainerError(Exception exception, MessageListenerContainer container) {
        log.error(
                "Container error for group {}: {}",
                container.getGroupId(),
                exception.getMessage(),
                exception);

        // In production, you might want to:
        // 1. Send alerts to monitoring system
        // 2. Restart the container
        // 3. Log to external monitoring service
    }
}
