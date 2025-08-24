package com.smmpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.transaction.KafkaTransactionManager;

/** PRODUCTION-READY Kafka Configuration with proper serializers, error handling, and DLQ */
@Slf4j
@Configuration
@EnableKafka
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaProperties kafkaProperties;

    private final ObjectMapper objectMapper;
    private KafkaErrorHandlerConfig errorHandlerConfig;
    private KafkaConsumerErrorConfiguration consumerErrorConfiguration;

    @org.springframework.beans.factory.annotation.Autowired
    public void setConsumerErrorConfiguration(
            @org.springframework.context.annotation.Lazy
                    KafkaConsumerErrorConfiguration consumerErrorConfiguration) {
        this.consumerErrorConfiguration = consumerErrorConfiguration;
    }

    // ...existing code...
    @org.springframework.beans.factory.annotation.Autowired
    public void setErrorHandlerConfig(
            @org.springframework.context.annotation.Lazy
                    KafkaErrorHandlerConfig errorHandlerConfig) {
        this.errorHandlerConfig = errorHandlerConfig;
    }

    // ===============================
    // TOPIC DEFINITIONS - FIXED NAMES
    // ===============================

    @Bean
    public NewTopic orderProcessingTopic() {
        return TopicBuilder.name("smm.order.processing")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "604800000", // 7 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic videoProcessingTopic() {
        return TopicBuilder.name("smm.video.processing")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic youtubeProcessingTopic() {
        return TopicBuilder.name("smm.youtube.processing")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic binomCampaignCreationTopic() {
        return TopicBuilder.name("smm.binom.campaign.creation")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic videoProcessingRetryTopic() {
        return TopicBuilder.name("smm.video.processing.retry")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "86400000", // 1 day
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic orderRefundTopic() {
        return TopicBuilder.name("smm.order.refund")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic offerAssignmentsTopic() {
        return TopicBuilder.name("smm.offer.assignments")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic offerAssignmentEventsTopic() {
        return TopicBuilder.name("smm.offer.assignment.events")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic orderStateUpdatesTopic() {
        return TopicBuilder.name("smm.order.state.updates")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "604800000", // 7 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic notificationsTopic() {
        return TopicBuilder.name("smm.notifications")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "604800000", // 7 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    // ===============================
    // PAYMENT TOPICS
    // ===============================

    @Bean
    public NewTopic paymentConfirmationsTopic() {
        return TopicBuilder.name("smm.payment.confirmations")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy",
                                "min.insync.replicas", "1")) // Critical for payment data
                .build();
    }

    @Bean
    public NewTopic paymentWebhooksTopic() {
        return TopicBuilder.name("smm.payment.webhooks")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy",
                                "min.insync.replicas", "1")) // Critical for payment data
                .build();
    }

    @Bean
    public NewTopic paymentRefundsTopic() {
        return TopicBuilder.name("smm.payment.refunds")
                .partitions(3)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy",
                                "min.insync.replicas", "1")) // Critical for payment data
                .build();
    }

    // ===============================
    // DLQ TOPICS
    // ===============================

    @Bean
    public NewTopic orderProcessingDlqTopic() {
        return TopicBuilder.name("smm.order.processing.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic videoProcessingDlqTopic() {
        return TopicBuilder.name("smm.video.processing.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic youtubeProcessingDlqTopic() {
        return TopicBuilder.name("smm.youtube.processing.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic offerAssignmentsDlqTopic() {
        return TopicBuilder.name("smm.offer.assignments.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic orderRefundDlqTopic() {
        return TopicBuilder.name("smm.order.refund.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic binomCampaignCreationDlqTopic() {
        return TopicBuilder.name("smm.binom.campaign.creation.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic videoProcessingRetryDlqTopic() {
        return TopicBuilder.name("smm.video.processing.retry.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic orderStateUpdatesDlqTopic() {
        return TopicBuilder.name("smm.order.state.updates.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic notificationsDlqTopic() {
        return TopicBuilder.name("smm.notifications.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy"))
                .build();
    }

    @Bean
    public NewTopic paymentConfirmationsDlqTopic() {
        return TopicBuilder.name("smm.payment.confirmations.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy",
                                "min.insync.replicas", "1")) // Critical for payment data
                .build();
    }

    @Bean
    public NewTopic paymentWebhooksDlqTopic() {
        return TopicBuilder.name("smm.payment.webhooks.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy",
                                "min.insync.replicas", "1")) // Critical for payment data
                .build();
    }

    @Bean
    public NewTopic paymentRefundsDlqTopic() {
        return TopicBuilder.name("smm.payment.refunds.dlq")
                .partitions(1)
                .replicas(1)
                .configs(
                        Map.of(
                                "retention.ms", "2592000000", // 30 days
                                "cleanup.policy", "delete",
                                "compression.type", "snappy",
                                "min.insync.replicas", "1")) // Critical for payment data
                .build();
    }

    // ===============================
    // PRODUCER CONFIGURATION
    // ===============================

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);

        // Transaction configuration
        configProps.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "smm-panel-tx");

        // JSON Serializer specific configs
        configProps.put(
                JsonSerializer.TYPE_MAPPINGS,
                "order:com.smmpanel.entity.Order,"
                        + "videoProcessing:com.smmpanel.entity.VideoProcessing,"
                        + "offerAssignment:com.smmpanel.dto.binom.OfferAssignmentRequest,"
                        + "offerAssignmentEvent:com.smmpanel.event.OfferAssignmentEvent,"
                        + "notification:java.util.Map,"
                        + "orderStateUpdate:java.util.Map");

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(configProps);
        factory.setTransactionIdPrefix("smm-panel-tx-");
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public KafkaTemplate<String, Object> defaultRetryTopicKafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public KafkaTransactionManager kafkaTransactionManager() {
        return new KafkaTransactionManager(producerFactory());
    }

    @Bean
    public AdminClient adminClient() {
        Map<String, Object> config = new HashMap<>();
        config.put(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaProperties.getBootstrapServers());
        config.put(
                org.apache.kafka.clients.admin.AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        config.put(
                org.apache.kafka.clients.admin.AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                10000);
        return AdminClient.create(config);
    }

    // ===============================
    // CONSUMER CONFIGURATION
    // ===============================

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getConsumer().getGroupId());
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        configProps.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        configProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        configProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
        configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);

        // JSON Deserializer specific configs
        configProps.put(
                JsonDeserializer.TRUSTED_PACKAGES,
                "com.smmpanel.entity,com.smmpanel.dto,com.smmpanel.event,java.util");
        configProps.put(
                JsonDeserializer.TYPE_MAPPINGS,
                "order:com.smmpanel.entity.Order,"
                        + "videoProcessing:com.smmpanel.entity.VideoProcessing,"
                        + "offerAssignment:com.smmpanel.dto.binom.OfferAssignmentRequest,"
                        + "offerAssignmentEvent:com.smmpanel.event.OfferAssignmentEvent,"
                        + "notification:java.util.Map,"
                        + "orderStateUpdate:java.util.Map");
        configProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        configProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(3000L);

        // Configure enhanced error handler with comprehensive retry and DLQ support
        factory.setCommonErrorHandler(consumerErrorConfiguration.defaultKafkaErrorHandler());
        return factory;
    }

    /** Specialized container factory for dead letter queue processing */
    @Bean("deadLetterQueueKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            deadLetterQueueKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(1); // DLQ processing should be single-threaded
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(5000L);

        // Use specialized DLQ error handler
        factory.setCommonErrorHandler(consumerErrorConfiguration.deadLetterQueueErrorHandler());
        return factory;
    }

    /** High priority container factory for critical processing */
    @Bean("highPriorityKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            highPriorityKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(5); // More threads for high priority
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(1000L); // Faster polling

        // Use high priority error handler
        factory.setCommonErrorHandler(consumerErrorConfiguration.highPriorityErrorHandler());
        return factory;
    }

    /** Order processing container factory with business logic error handling */
    @Bean("orderProcessingKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            orderProcessingKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(3000L);

        // Use order-specific error handler
        factory.setCommonErrorHandler(consumerErrorConfiguration.orderProcessingErrorHandler());
        return factory;
    }

    /** Payment confirmation container factory for real-time payment processing */
    @Bean("paymentConfirmationContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            paymentConfirmationContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(2); // Less concurrency for payment processing
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties()
                .setPollTimeout(1000L); // Faster polling for real-time payments

        // Use high priority error handler for critical payment processing
        factory.setCommonErrorHandler(consumerErrorConfiguration.highPriorityErrorHandler());
        return factory;
    }

    // ===============================
    // HELPER METHODS
    // ===============================

}
