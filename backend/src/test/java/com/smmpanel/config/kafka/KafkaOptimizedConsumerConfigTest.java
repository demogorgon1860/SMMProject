package com.smmpanel.config.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for Kafka Optimized Consumer Configuration
 * Verifies throughput requirements and configuration settings
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "app.features.kafka-processing.enabled=true"
})
class KafkaOptimizedConsumerConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void shouldHaveOrderEventConsumerFactory() {
        ConsumerFactory<String, Object> factory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("orderEventConsumerFactory");
        
        assertThat(factory).isNotNull();
        
        // Verify high-throughput configuration
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000)
            .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 50000)
            .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100)
            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "smm-order-events-optimized-group");
    }

    @Test
    void shouldHaveVideoProcessingBatchConsumerFactory() {
        ConsumerFactory<String, Object> factory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("videoProcessingBatchConsumerFactory");
        
        assertThat(factory).isNotNull();
        
        // Verify batch processing configuration
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50)
            .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 10000)
            .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500)
            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "smm-video-processing-batch-group");
    }

    @Test
    void shouldHavePaymentConfirmationConsumerFactory() {
        ConsumerFactory<String, Object> factory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("paymentConfirmationConsumerFactory");
        
        assertThat(factory).isNotNull();
        
        // Verify real-time processing configuration
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
            .containsEntry(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1)
            .containsEntry(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 10)
            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "smm-payment-confirmations-realtime-group");
    }

    @Test
    void shouldHaveOrderEventContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
            (ConcurrentKafkaListenerContainerFactory<String, Object>) 
                applicationContext.getBean("orderEventContainerFactory");
        
        assertThat(factory).isNotNull();
        
        // Verify batch listener configuration
        assertThat(factory.isBatchListener()).isFalse();
        
        ContainerProperties props = factory.getContainerProperties();
        assertThat(props.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(props.isSyncCommits()).isTrue();
    }

    @Test
    void shouldHaveVideoProcessingBatchContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
            (ConcurrentKafkaListenerContainerFactory<String, Object>) 
                applicationContext.getBean("videoProcessingBatchContainerFactory");
        
        assertThat(factory).isNotNull();
        
        // Verify batch processing setup
        assertThat(factory.isBatchListener()).isTrue();
        
        ContainerProperties props = factory.getContainerProperties();
        assertThat(props.getAckMode()).isEqualTo(ContainerProperties.AckMode.BATCH);
        assertThat(props.getPollTimeout()).isEqualTo(5000L);
    }

    @Test
    void shouldHavePaymentConfirmationContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
            (ConcurrentKafkaListenerContainerFactory<String, Object>) 
                applicationContext.getBean("paymentConfirmationContainerFactory");
        
        assertThat(factory).isNotNull();
        
        // Verify real-time processing setup
        assertThat(factory.isBatchListener()).isFalse();
        
        ContainerProperties props = factory.getContainerProperties();
        assertThat(props.getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(props.getPollTimeout()).isEqualTo(100L);
    }

    @Test
    void shouldHaveSnappyCompressionEnabled() {
        ConsumerFactory<String, Object> orderFactory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("orderEventConsumerFactory");
        
        ConsumerFactory<String, Object> videoFactory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("videoProcessingBatchConsumerFactory");
        
        ConsumerFactory<String, Object> paymentFactory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("paymentConfirmationConsumerFactory");
        
        // Verify compression is enabled across all factories
        assertThat(orderFactory.getConfigurationProperties())
            .containsEntry("compression.type", "snappy");
        assertThat(videoFactory.getConfigurationProperties())
            .containsEntry("compression.type", "snappy");
        assertThat(paymentFactory.getConfigurationProperties())
            .containsEntry("compression.type", "snappy");
    }

    @Test
    void shouldHaveProperIsolationLevel() {
        ConsumerFactory<String, Object> factory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("orderEventConsumerFactory");
        
        // Verify exactly-once semantics
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    }

    @Test
    void shouldHavePartitionAssignmentStrategy() {
        ConsumerFactory<String, Object> factory = 
            (ConsumerFactory<String, Object>) applicationContext.getBean("orderEventConsumerFactory");
        
        // Verify cooperative sticky assignor for better load balancing
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, 
                "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
    }

    @Test
    void shouldHaveMessageDeduplicationService() {
        assertThat(applicationContext.containsBean("messageDeduplicationService")).isTrue();
        
        Object deduplicationService = applicationContext.getBean("messageDeduplicationService");
        assertThat(deduplicationService).isNotNull();
        assertThat(deduplicationService.getClass().getSimpleName())
            .isEqualTo("MessageDeduplicationService");
    }
}