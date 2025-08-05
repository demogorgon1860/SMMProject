package com.smmpanel.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ENHANCED KAFKA CONSUMER ERROR CONFIGURATION
 *
 * Provides comprehensive error handling for Kafka consumers:
 * 1. Configurable retry policies with exponential/fixed backoff
 * 2. Dead Letter Queue (DLQ) publishing with metadata enhancement
 * 3. Exception classification and handling strategies
 * 4. Error metrics and monitoring
 * 5. Recovery mechanisms for different failure scenarios
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerErrorConfiguration {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.kafka.error-handling.max-retries:5}")
    private int maxRetries;

    @Value("${app.kafka.error-handling.initial-interval:1000}")
    private long initialInterval;

    @Value("${app.kafka.error-handling.max-interval:30000}")
    private long maxInterval;

    @Value("${app.kafka.error-handling.multiplier:2.0}")
    private double multiplier;

    @Value("${app.kafka.error-handling.use-exponential-backoff:true}")
    private boolean useExponentialBackoff;

    @Value("${app.kafka.error-handling.include-stack-trace:false}")
    private boolean includeStackTrace;

    // Error metrics
    private final AtomicLong totalErrorsCount = new AtomicLong(0);
    private final AtomicLong retriedErrorsCount = new AtomicLong(0);
    private final AtomicLong dlqSentCount = new AtomicLong(0);
    private final AtomicLong nonRetryableErrorsCount = new AtomicLong(0);

    /**
     * Creates enhanced error handler with comprehensive retry and DLQ support
     */
    @Bean("defaultKafkaErrorHandler")
    public CommonErrorHandler defaultKafkaErrorHandler() {
        log.info("Configuring Kafka error handler with {} retries, {}ms initial interval, {}ms max interval", 
                maxRetries, initialInterval, maxInterval);

        // Create dead letter publishing recoverer with enhanced metadata
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                this::resolveDlqDestination
        );

        // Enhance DLQ messages with additional metadata
        recoverer.setHeadersFunction((consumerRecord, exception) -> {
            Map<String, Object> headers = new HashMap<>();
            headers.put("dlt-original-topic", consumerRecord.topic());
            headers.put("dlt-original-partition", consumerRecord.partition());
            headers.put("dlt-original-offset", consumerRecord.offset());
            headers.put("dlt-original-timestamp", consumerRecord.timestamp());
            headers.put("dlt-exception-class", exception.getClass().getSimpleName());
            headers.put("dlt-exception-message", exception.getMessage());
            headers.put("dlt-failure-timestamp", LocalDateTime.now().toString());
            headers.put("dlt-retry-attempts", maxRetries);
            
            if (includeStackTrace) {
                headers.put("dlt-stack-trace", getStackTrace(exception));
            }
            
            return headers;
        });

        // Create backoff strategy
        BackOff backOff = createBackOffStrategy();
        
        // Create error handler with custom recoverer
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        
        // Configure non-retryable exceptions
        configureNonRetryableExceptions(errorHandler);
        
        // Add custom error handling logic
        errorHandler.setRetryListeners((consumerRecord, exception, deliveryAttempt) -> {
            retriedErrorsCount.incrementAndGet();
            log.warn("Retrying message processing (attempt {}/{}): topic={}, partition={}, offset={}, error={}", 
                    deliveryAttempt, maxRetries, consumerRecord.topic(), 
                    consumerRecord.partition(), consumerRecord.offset(), exception.getMessage());
        });

        return errorHandler;
    }

    /**
     * Creates specialized error handler for dead letter queue processing
     */
    @Bean("deadLetterQueueErrorHandler")
    public CommonErrorHandler deadLetterQueueErrorHandler() {
        // DLQ processing should have different strategy - fewer retries, longer intervals
        BackOff dlqBackOff = new FixedBackOff(5000L, 2L); // 5 seconds, 2 retries
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            (consumerRecord, exception) -> {
                dlqSentCount.incrementAndGet();
                log.error("DLQ message processing failed permanently: topic={}, partition={}, offset={}, error={}", 
                        consumerRecord.topic(), consumerRecord.partition(), 
                        consumerRecord.offset(), exception.getMessage(), exception);
                
                // In production, this might trigger alerts or manual intervention
                sendDlqProcessingFailureAlert(consumerRecord, exception);
            },
            dlqBackOff
        );
        
        // DLQ processing should only retry on transient errors
        errorHandler.addNotRetryableExceptions(
            IllegalArgumentException.class,
            NumberFormatException.class,
            ClassCastException.class,
            org.springframework.kafka.support.serializer.DeserializationException.class,
            org.springframework.dao.DataIntegrityViolationException.class
        );
        
        return errorHandler;
    }

    /**
     * Creates specialized error handler for high-priority processing
     */
    @Bean("highPriorityErrorHandler")
    public CommonErrorHandler highPriorityErrorHandler() {
        // High priority processing: more aggressive retries, shorter intervals
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(500L);   // Start with 500ms
        backOff.setMaxInterval(10000L);     // Max 10 seconds
        backOff.setMultiplier(1.5);         // Gentler exponential growth
        backOff.setMaxElapsedTime(60000L);  // Give up after 1 minute
        
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                this::resolveDlqDestination
        );
        
        // Add high-priority specific metadata
        recoverer.setHeadersFunction((consumerRecord, exception) -> {
            Map<String, Object> headers = new HashMap<>();
            headers.put("dlt-priority", "HIGH");
            headers.put("dlt-original-topic", consumerRecord.topic());
            headers.put("dlt-original-partition", consumerRecord.partition());
            headers.put("dlt-original-offset", consumerRecord.offset());
            headers.put("dlt-exception-class", exception.getClass().getSimpleName());
            headers.put("dlt-exception-message", exception.getMessage());
            headers.put("dlt-failure-timestamp", LocalDateTime.now().toString());
            return headers;
        });
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        configureNonRetryableExceptions(errorHandler);
        
        return errorHandler;
    }

    /**
     * Creates custom error handler for order processing with business logic
     */
    @Bean("orderProcessingErrorHandler")
    public CommonErrorHandler orderProcessingErrorHandler() {
        ConsumerRecordRecoverer orderRecoverer = (consumerRecord, exception) -> {
            dlqSentCount.incrementAndGet();
            log.error("Order processing failed permanently: {}", consumerRecord, exception);
            
            // Custom order processing failure logic
            handleOrderProcessingFailure(consumerRecord, exception);
            
            // Send to DLQ with order-specific metadata
            sendOrderToDlq(consumerRecord, exception);
        };
        
        BackOff orderBackOff = createBackOffStrategy();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(orderRecoverer, orderBackOff);
        
        // Order-specific non-retryable exceptions
        errorHandler.addNotRetryableExceptions(
            IllegalArgumentException.class,
            com.smmpanel.exception.InsufficientBalanceException.class,
            com.smmpanel.exception.UserValidationException.class,
            com.smmpanel.exception.ServiceNotFoundException.class
        );
        
        return errorHandler;
    }

    /**
     * Creates backoff strategy based on configuration
     */
    private BackOff createBackOffStrategy() {
        if (useExponentialBackoff) {
            ExponentialBackOff backOff = new ExponentialBackOff();
            backOff.setInitialInterval(initialInterval);
            backOff.setMaxInterval(maxInterval);
            backOff.setMultiplier(multiplier);
            backOff.setMaxElapsedTime(maxInterval * maxRetries);
            return backOff;
        } else {
            return new FixedBackOff(initialInterval, maxRetries);
        }
    }

    /**
     * Configures exceptions that should not be retried
     */
    private void configureNonRetryableExceptions(DefaultErrorHandler errorHandler) {
        errorHandler.addNotRetryableExceptions(
            // Serialization/Deserialization errors
            org.springframework.kafka.support.serializer.DeserializationException.class,
            com.fasterxml.jackson.core.JsonParseException.class,
            com.fasterxml.jackson.databind.JsonMappingException.class,
            
            // Validation errors
            IllegalArgumentException.class,
            NumberFormatException.class,
            ClassCastException.class,
            
            // Business logic errors
            com.smmpanel.exception.UserValidationException.class,
            com.smmpanel.exception.OrderValidationException.class,
            com.smmpanel.exception.ServiceNotFoundException.class,
            com.smmpanel.exception.UserNotFoundException.class,
            
            // Data integrity errors
            org.springframework.dao.DataIntegrityViolationException.class,
            jakarta.validation.ConstraintViolationException.class
        );
        
        // Add retry listener to track non-retryable exceptions
        errorHandler.setRetryListeners((consumerRecord, exception, deliveryAttempt) -> {
            totalErrorsCount.incrementAndGet();
            
            if (deliveryAttempt == 1) {
                // Check if this is a non-retryable exception
                if (isNonRetryableException(exception)) {
                    nonRetryableErrorsCount.incrementAndGet();
                    log.warn("Non-retryable exception encountered: topic={}, partition={}, offset={}, error={}", 
                            consumerRecord.topic(), consumerRecord.partition(), 
                            consumerRecord.offset(), exception.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * Resolves DLQ destination based on original topic
     */
    private TopicPartition resolveDlqDestination(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
        String dlqTopic = getDlqTopicName(consumerRecord.topic());
        return new TopicPartition(dlqTopic, 0); // All DLQ messages go to partition 0
    }

    /**
     * Maps original topic to corresponding DLQ topic
     */
    private String getDlqTopicName(String originalTopic) {
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
            default -> originalTopic + ".dlq";
        };
    }

    /**
     * Checks if exception is non-retryable
     */
    private boolean isNonRetryableException(Exception exception) {
        return exception instanceof IllegalArgumentException ||
               exception instanceof NumberFormatException ||
               exception instanceof ClassCastException ||
               exception instanceof org.springframework.kafka.support.serializer.DeserializationException ||
               exception instanceof com.smmpanel.exception.UserValidationException ||
               exception instanceof com.smmpanel.exception.OrderValidationException ||
               exception instanceof com.smmpanel.exception.ServiceNotFoundException ||
               exception instanceof org.springframework.dao.DataIntegrityViolationException;
    }

    /**
     * Handles order processing failures with business logic
     */
    private void handleOrderProcessingFailure(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
        try {
            // Extract order information if possible
            log.error("Handling order processing failure for record: topic={}, partition={}, offset={}", 
                    consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset());
            
            // Here you could:
            // 1. Update order status to FAILED
            // 2. Refund balance if payment was processed
            // 3. Send notification to user
            // 4. Log to audit trail
            
        } catch (Exception e) {
            log.error("Failed to handle order processing failure", e);
        }
    }

    /**
     * Sends order to DLQ with enhanced metadata
     */
    private void sendOrderToDlq(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
        try {
            String dlqTopic = getDlqTopicName(consumerRecord.topic());
            
            Message<Object> dlqMessage = MessageBuilder
                    .withPayload(consumerRecord.value())
                    .setHeader(KafkaHeaders.TOPIC, dlqTopic)
                    .setHeader("dlt-original-topic", consumerRecord.topic())
                    .setHeader("dlt-original-partition", consumerRecord.partition())
                    .setHeader("dlt-original-offset", consumerRecord.offset())
                    .setHeader("dlt-exception-class", exception.getClass().getSimpleName())
                    .setHeader("dlt-exception-message", exception.getMessage())
                    .setHeader("dlt-failure-timestamp", LocalDateTime.now().toString())
                    .setHeader("dlt-message-type", "ORDER_PROCESSING_FAILURE")
                    .build();
            
            kafkaTemplate.send(dlqMessage);
            
            log.info("Order sent to DLQ: topic={}, dlqTopic={}", consumerRecord.topic(), dlqTopic);
            
        } catch (Exception e) {
            log.error("Failed to send order to DLQ", e);
        }
    }

    /**
     * Sends alert for DLQ processing failures
     */
    private void sendDlqProcessingFailureAlert(ConsumerRecord<?, ?> consumerRecord, Exception exception) {
        log.error("CRITICAL: DLQ processing failed - manual intervention required: topic={}, partition={}, offset={}", 
                consumerRecord.topic(), consumerRecord.partition(), consumerRecord.offset());
        
        // In production, this would trigger:
        // 1. PagerDuty/Slack alerts
        // 2. Email notifications to operations team
        // 3. Dashboard alerts
        // 4. Metrics updates
    }

    /**
     * Gets stack trace as string
     */
    private String getStackTrace(Exception exception) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Provides error metrics for monitoring
     */
    @Bean("kafkaErrorMetrics")
    public Map<String, Object> kafkaErrorMetrics() {
        return Map.of(
            "totalErrors", totalErrorsCount.get(),
            "retriedErrors", retriedErrorsCount.get(),
            "dlqSentCount", dlqSentCount.get(),
            "nonRetryableErrors", nonRetryableErrorsCount.get(),
            "errorRate", totalErrorsCount.get() > 0 ? 
                (double) nonRetryableErrorsCount.get() / totalErrorsCount.get() : 0.0
        );
    }
}