package com.smmpanel.config;

import com.smmpanel.dto.kafka.VideoProcessingMessage;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * KAFKA CONFIGURATION: Video Processing Message Queue
 *
 * <p>Features: 1. Dedicated topic for video processing messages 2. JSON serialization for complex
 * message objects 3. Producer configuration optimized for throughput 4. Consumer configuration with
 * proper error handling 5. Auto-topic creation with appropriate partitions
 */
@Slf4j
@Configuration
@EnableKafka
public class KafkaVideoProcessingConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${app.kafka.video-processing.topic:video.processing.queue}")
    private String videoProcessingTopic;

    @Value("${app.kafka.video-processing.partitions:3}")
    private int topicPartitions;

    @Value("${app.kafka.video-processing.replication-factor:1}")
    private short replicationFactor;

    @Value("${app.kafka.video-processing.consumer.group-id:video-processing-group}")
    private String consumerGroupId;

    @Value("${app.kafka.video-processing.consumer.max-poll-records:10}")
    private int maxPollRecords;

    @Value("${app.kafka.video-processing.consumer.session-timeout:30000}")
    private int sessionTimeoutMs;

    @Value("${app.kafka.video-processing.producer.batch-size:16384}")
    private int batchSize;

    @Value("${app.kafka.video-processing.producer.linger-ms:5}")
    private int lingerMs;

    /**
     * TOPIC CREATION: video.processing.queue Auto-creates topic with optimal configuration for
     * video processing workload
     */
    @Bean("videoProcessingQueueTopic")
    public NewTopic videoProcessingTopic() {
        NewTopic topic = new NewTopic(videoProcessingTopic, topicPartitions, replicationFactor);

        // Configure topic properties for video processing workload
        Map<String, String> configs = new HashMap<>();
        configs.put("cleanup.policy", "delete");
        configs.put("retention.ms", "604800000"); // 7 days retention
        configs.put("segment.ms", "86400000"); // 1 day segments
        configs.put("compression.type", "snappy"); // Efficient compression
        configs.put("message.max.bytes", "1048576"); // 1MB max message size

        topic.configs(configs);

        log.info(
                "Creating Kafka topic: {} with {} partitions, replication factor: {}",
                videoProcessingTopic,
                topicPartitions,
                replicationFactor);

        return topic;
    }

    /** KAFKA ADMIN CLIENT: For topic management */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        configs.put(AdminClientConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 300000);

        return new KafkaAdmin(configs);
    }

    /**
     * PRODUCER FACTORY: Optimized for video processing messages High throughput configuration with
     * JSON serialization
     */
    @Bean
    public ProducerFactory<String, VideoProcessingMessage> videoProcessingProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // Basic configuration
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Performance optimization
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB buffer

        // Reliability configuration
        configProps.put(ProducerConfig.ACKS_CONFIG, "all"); // Wait for all replicas
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000); // 2 minutes
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);

        // Idempotence for exactly-once semantics
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        // JSON serializer configuration
        configProps.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        log.info(
                "Configured Kafka producer for video processing: batch={}, linger={}ms,"
                        + " compression=snappy",
                batchSize,
                lingerMs);

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /** KAFKA TEMPLATE: For sending video processing messages */
    @Bean
    public KafkaTemplate<String, VideoProcessingMessage> videoProcessingKafkaTemplate() {
        KafkaTemplate<String, VideoProcessingMessage> template =
                new KafkaTemplate<>(videoProcessingProducerFactory());
        template.setDefaultTopic(videoProcessingTopic);

        // Fix: Make a mutable copy of the configuration properties before modifying
        Map<String, Object> mutableProps =
                new HashMap<>(template.getProducerFactory().getConfigurationProperties());
        mutableProps.put("client.id", "video-processing-producer");
        // If you need to use these properties, set them back or use as needed
        // template.getProducerFactory().updateConfigurationProperties(mutableProps); // If such a
        // method exists

        log.info(
                "Configured KafkaTemplate for video processing with default topic: {}",
                videoProcessingTopic);
        return template;
    }

    /**
     * CONSUMER FACTORY: Optimized for video processing message consumption Configured for reliable
     * processing with proper error handling
     */
    @Bean
    public ConsumerFactory<String, VideoProcessingMessage> videoProcessingConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();

        // Basic configuration
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // Consumer behavior
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual commit for reliability
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, sessionTimeoutMs / 3);

        // Processing timeouts
        configProps.put(
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 minutes for processing
        configProps.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 40000);
        configProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        configProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        // JSON deserializer configuration
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.smmpanel.dto.kafka");
        configProps.put(
                JsonDeserializer.VALUE_DEFAULT_TYPE, VideoProcessingMessage.class.getName());
        configProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        log.info(
                "Configured Kafka consumer for video processing: group={}, max-poll={},"
                        + " session-timeout={}ms",
                consumerGroupId,
                maxPollRecords,
                sessionTimeoutMs);

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * LISTENER CONTAINER FACTORY: For @KafkaListener configuration Optimized for video processing
     * workload with proper error handling
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, VideoProcessingMessage>
            videoProcessingKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, VideoProcessingMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(videoProcessingConsumerFactory());

        // Concurrency configuration (number of consumer threads)
        factory.setConcurrency(Math.min(topicPartitions, 3)); // Max 3 concurrent consumers

        // Container properties
        ContainerProperties containerProps = factory.getContainerProperties();
        containerProps.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE); // Manual commit
        containerProps.setSyncCommits(true); // Synchronous commits for reliability
        containerProps.setCommitRetries(3);

        // Error handling
        factory.setCommonErrorHandler(
                new org.springframework.kafka.listener.DefaultErrorHandler(
                        (record, exception) -> {
                            log.error(
                                    "Failed to process video processing message: key={},"
                                            + " partition={}, offset={}, error={}",
                                    record.key(),
                                    record.partition(),
                                    record.offset(),
                                    exception.getMessage(),
                                    exception);
                        },
                        new org.springframework.util.backoff.FixedBackOff(
                                1000L, 3L) // 1 second interval, 3 retry attempts
                        ));

        log.info(
                "Configured Kafka listener container factory with concurrency: {}, manual ack mode",
                Math.min(topicPartitions, 3));

        return factory;
    }

    /** HEALTH INDICATOR: Monitor Kafka connectivity for video processing */
    @Bean
    public org.springframework.boot.actuate.health.HealthIndicator
            videoProcessingKafkaHealthIndicator() {
        return () -> {
            try {
                // Simple health check by attempting to get metadata
                KafkaTemplate<String, VideoProcessingMessage> template =
                        videoProcessingKafkaTemplate();
                template.partitionsFor(videoProcessingTopic);

                return org.springframework.boot.actuate.health.Health.up()
                        .withDetail("topic", videoProcessingTopic)
                        .withDetail("partitions", topicPartitions)
                        .withDetail("bootstrap-servers", bootstrapServers)
                        .withDetail("consumer-group", consumerGroupId)
                        .build();

            } catch (Exception e) {
                return org.springframework.boot.actuate.health.Health.down()
                        .withDetail("error", e.getMessage())
                        .withDetail("topic", videoProcessingTopic)
                        .build();
            }
        };
    }
}
