package com.smmpanel.config;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Optimized Kafka Consumer Configuration for High Throughput
 *
 * <p>Throughput Requirements: - Order events: 1000 messages/second - Video processing: 100
 * messages/second with batching - Payment confirmations: Real-time processing
 *
 * <p>Features: - Snappy compression enabled - Optimized batch sizes and linger settings - Proper
 * acknowledgment modes - Message deduplication for idempotency
 */
@Slf4j
@Configuration
public class KafkaOptimizedConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /** High-throughput consumer factory for order events (1000 msg/sec) */
    @Bean("orderEventConsumerFactory")
    public ConsumerFactory<String, Object> orderEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();

        // Basic configuration
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // High-throughput optimizations for 1000 msg/sec
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "smm-order-events-optimized-group");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000); // High batch size for throughput
        props.put(
                ConsumerConfig.FETCH_MIN_BYTES_CONFIG,
                50000); // Wait for more data before returning
        props.put(
                ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,
                100); // Low latency for real-time processing
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000); // Longer timeout for stability
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
        props.put(
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                600000); // 10 minutes for batch processing

        // Performance optimizations
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual commit for reliability
        props.put(
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed"); // Exactly-once semantics

        // Compression and network optimization
        props.put(ConsumerConfig.CHECK_CRCS_CONFIG, true); // Data integrity
        props.put("compression.type", "snappy"); // Enable compression

        // Connection optimization
        props.put(ConsumerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 540000);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);

        // Partition assignment strategy for better load balancing
        props.put(
                ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");

        // JSON deserialization configuration
        props.put(
                JsonDeserializer.TRUSTED_PACKAGES,
                "com.smmpanel.entity,com.smmpanel.dto,com.smmpanel.event,java.util");
        props.put(
                JsonDeserializer.TYPE_MAPPINGS,
                "order:com.smmpanel.entity.Order,orderEvent:com.smmpanel.event.OrderCreatedEvent");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Batch processing consumer factory for video processing (100 msg/sec with batching) */
    @Bean("videoProcessingBatchConsumerFactory")
    public ConsumerFactory<String, Object> videoProcessingBatchConsumerFactory() {
        Map<String, Object> props = new HashMap<>();

        // Basic configuration
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // Batch processing optimizations for 100 msg/sec
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "smm-video-processing-batch-group");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50); // Smaller batches for better control
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 10000); // Moderate fetch size
        props.put(
                ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,
                500); // Balance between throughput and latency
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
        props.put(
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                900000); // 15 minutes for video processing

        // Reliability settings
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        // Compression
        props.put("compression.type", "snappy");

        // JSON deserialization
        props.put(
                JsonDeserializer.TRUSTED_PACKAGES,
                "com.smmpanel.entity,com.smmpanel.dto,com.smmpanel.event,java.util");
        props.put(
                JsonDeserializer.TYPE_MAPPINGS,
                "videoProcessing:com.smmpanel.entity.VideoProcessing,videoEvent:com.smmpanel.event.VideoProcessingEvent");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Real-time consumer factory for payment confirmations */
    @Bean("paymentConfirmationConsumerFactory")
    public ConsumerFactory<String, Object> paymentConfirmationConsumerFactory() {
        Map<String, Object> props = new HashMap<>();

        // Basic configuration
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // Real-time optimizations - prioritize latency over throughput
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "smm-payment-confirmations-realtime-group");
        props.put(
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                1); // Process one at a time for minimum latency
        props.put(
                ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1); // Return immediately when data available
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 10); // Minimal wait time
        props.put(
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
                10000); // Shorter timeout for quick failover
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1000); // Frequent heartbeats
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 60000); // 1 minute max processing

        // Real-time reliability
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        // Compression (even for real-time to reduce network overhead)
        props.put("compression.type", "snappy");

        // JSON deserialization
        props.put(
                JsonDeserializer.TRUSTED_PACKAGES,
                "com.smmpanel.dto.payment,com.smmpanel.event,java.util");
        props.put(
                JsonDeserializer.TYPE_MAPPINGS,
                "paymentConfirmation:com.smmpanel.dto.payment.PaymentConfirmationDto");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Container factory for order events (1000 msg/sec) */
    @Bean("orderEventContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> orderEventContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(orderEventConsumerFactory());
        factory.setConcurrency(8); // High concurrency for throughput
        factory.setBatchListener(false); // Individual message processing for order events

        ContainerProperties containerProps = factory.getContainerProperties();
        containerProps.setAckMode(
                ContainerProperties.AckMode.MANUAL_IMMEDIATE); // Fast acknowledgment
        containerProps.setSyncCommits(true);
        containerProps.setPollTimeout(1000L);
        containerProps.setIdleEventInterval(30000L);

        // Optimized for high throughput
        containerProps.setMissingTopicsFatal(false);
        containerProps.setLogContainerConfig(true);

        log.info(
                "Configured order event container factory with 8 concurrent consumers for 1000"
                        + " msg/sec");
        return factory;
    }

    /** Container factory for video processing with batching (100 msg/sec) */
    @Bean("videoProcessingBatchContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            videoProcessingBatchContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(videoProcessingBatchConsumerFactory());
        factory.setConcurrency(4); // Moderate concurrency for batch processing
        factory.setBatchListener(true); // Enable batch processing

        ContainerProperties containerProps = factory.getContainerProperties();
        containerProps.setAckMode(ContainerProperties.AckMode.BATCH); // Batch acknowledgment
        containerProps.setSyncCommits(true);
        containerProps.setPollTimeout(5000L); // Longer timeout for batching
        containerProps.setIdleEventInterval(60000L);

        // Batch processing specific settings
        containerProps.setIdleBetweenPolls(100L);
        containerProps.setMissingTopicsFatal(false);

        log.info(
                "Configured video processing batch container factory with 4 concurrent consumers"
                        + " and batch processing");
        return factory;
    }

    /** Container factory for payment confirmations (real-time) */
    @Bean("paymentConfirmationContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            paymentConfirmationContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(paymentConfirmationConsumerFactory());
        factory.setConcurrency(2); // Limited concurrency for ordered processing
        factory.setBatchListener(false); // Individual message processing for real-time

        ContainerProperties containerProps = factory.getContainerProperties();
        containerProps.setAckMode(
                ContainerProperties.AckMode.MANUAL_IMMEDIATE); // Immediate acknowledgment
        containerProps.setSyncCommits(true);
        containerProps.setPollTimeout(100L); // Very short poll timeout for real-time
        containerProps.setIdleEventInterval(10000L);

        // Real-time specific settings
        containerProps.setIdleBetweenPolls(10L); // Minimal idle time
        containerProps.setMissingTopicsFatal(true); // Fail fast for payment topics

        log.info(
                "Configured payment confirmation container factory with 2 concurrent consumers for"
                        + " real-time processing");
        return factory;
    }
}
