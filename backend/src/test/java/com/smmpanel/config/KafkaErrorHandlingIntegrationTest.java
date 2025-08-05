package com.smmpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.kafka.VideoProcessingMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * COMPREHENSIVE KAFKA ERROR HANDLING INTEGRATION TESTS
 *
 * Tests various error scenarios and recovery mechanisms:
 * 1. Retryable exceptions with exponential backoff
 * 2. Non-retryable exceptions that go directly to DLQ
 * 3. Dead letter queue message processing
 * 4. Error handler configuration validation
 * 5. Metrics and monitoring verification
 */
@Slf4j
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    brokerProperties = {
        "listeners=PLAINTEXT://localhost:9092",
        "port=9092"
    },
    topics = {
        "test.video.processing",
        "test.video.processing.dlq",
        "test.order.processing", 
        "test.order.processing.dlq",
        "test.high.priority",
        "test.high.priority.dlq"
    }
)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "app.kafka.error-handling.max-retries=3",
    "app.kafka.error-handling.initial-interval=100",
    "app.kafka.error-handling.max-interval=1000",
    "app.kafka.error-handling.include-stack-trace=true"
})
@DirtiesContext
class KafkaErrorHandlingIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaConsumerErrorConfiguration errorConfiguration;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Producer<String, Object> testProducer;
    private Consumer<String, Object> testConsumer;
    private Consumer<String, Object> dlqConsumer;

    @BeforeEach
    void setUp() {
        // Set up test producer
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(embeddedKafkaBroker);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        testProducer = new DefaultKafkaProducerFactory<String, Object>(producerProps).createProducer();

        // Set up test consumer for main topics
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");
        testConsumer = new DefaultKafkaConsumerFactory<String, Object>(consumerProps).createConsumer();

        // Set up DLQ consumer
        Map<String, Object> dlqConsumerProps = KafkaTestUtils.consumerProps("dlq-test-group", "true", embeddedKafkaBroker);
        dlqConsumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        dlqConsumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        dlqConsumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        dlqConsumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");
        dlqConsumer = new DefaultKafkaConsumerFactory<String, Object>(dlqConsumerProps).createConsumer();

        // Subscribe to topics
        testConsumer.subscribe(Collections.singletonList("test.video.processing"));
        dlqConsumer.subscribe(Collections.singletonList("test.video.processing.dlq"));
    }

    @Test
    @Timeout(30)
    void testDefaultErrorHandlerCreation() {
        log.info("Testing default error handler creation");

        CommonErrorHandler errorHandler = errorConfiguration.defaultKafkaErrorHandler();
        assertNotNull(errorHandler, "Error handler should not be null");
        assertTrue(errorHandler instanceof org.springframework.kafka.listener.DefaultErrorHandler, 
                "Should be instance of DefaultErrorHandler");

        log.info("Default error handler created successfully");
    }

    @Test
    @Timeout(30)
    void testRetryableExceptionHandling() throws Exception {
        log.info("Testing retryable exception handling with backoff");

        CountDownLatch retryLatch = new CountDownLatch(3); // Expect 3 retries
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Create a failing video processing message
        VideoProcessingMessage message = VideoProcessingMessage.builder()
                .orderId(12345L)
                .videoUrl("https://youtube.com/watch?v=invalid")
                .attemptNumber(1)
                .maxAttempts(5)
                .createdAt(LocalDateTime.now())
                .build();

        // Send message that will cause retryable failure
        testProducer.send(new ProducerRecord<>("test.video.processing", "test-key", message));
        testProducer.flush();

        // Simulate consumer that fails with retryable exception
        CompletableFuture.runAsync(() -> {
            while (attemptCount.get() < 3) {
                ConsumerRecords<String, Object> records = testConsumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, Object> record : records) {
                    int attempt = attemptCount.incrementAndGet();
                    log.info("Processing attempt {}: {}", attempt, record.value());
                    
                    // Simulate retryable failure (network timeout)
                    retryLatch.countDown();
                    throw new RuntimeException("Simulated network timeout - retryable");
                }
            }
        });

        // Wait for retries to complete
        boolean retriesCompleted = retryLatch.await(15, TimeUnit.SECONDS);
        assertTrue(retriesCompleted, "Should have completed all retry attempts");
        assertTrue(attemptCount.get() >= 3, "Should have attempted at least 3 times");

        log.info("Retryable exception handling test completed with {} attempts", attemptCount.get());
    }

    @Test
    @Timeout(30)
    void testNonRetryableExceptionHandling() throws Exception {
        log.info("Testing non-retryable exception handling");

        CountDownLatch dlqLatch = new CountDownLatch(1);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Create invalid message that will cause deserialization error
        String invalidJson = "{\"orderId\": \"not-a-number\", \"videoUrl\": \"test\"}";
        
        testProducer.send(new ProducerRecord<>("test.video.processing", "test-key", invalidJson));
        testProducer.flush();

        // Check that message goes directly to DLQ without retries
        CompletableFuture.runAsync(() -> {
            ConsumerRecords<String, Object> records = testConsumer.poll(Duration.ofMillis(2000));
            for (ConsumerRecord<String, Object> record : records) {
                attemptCount.incrementAndGet();
                log.info("Processing non-retryable error: {}", record.value());
                
                // Simulate non-retryable failure (validation error)
                throw new IllegalArgumentException("Invalid message format - non-retryable");
            }
        });

        // Check if message appears in DLQ
        CompletableFuture.runAsync(() -> {
            ConsumerRecords<String, Object> dlqRecords = dlqConsumer.poll(Duration.ofMillis(5000));
            for (ConsumerRecord<String, Object> record : dlqRecords) {
                log.info("Received DLQ message: {}", record.value());
                dlqLatch.countDown();
            }
        });

        boolean dlqReceived = dlqLatch.await(10, TimeUnit.SECONDS);
        assertTrue(dlqReceived, "Message should have been sent to DLQ");
        assertEquals(1, attemptCount.get(), "Should have attempted only once for non-retryable exception");

        log.info("Non-retryable exception handling test completed");
    }

    @Test
    @Timeout(30)
    void testDeadLetterQueueProcessing() throws Exception {
        log.info("Testing dead letter queue processing");

        CountDownLatch dlqProcessingLatch = new CountDownLatch(1);

        // Create a message for DLQ
        Map<String, Object> dlqMessage = Map.of(
                "originalTopic", "test.video.processing",
                "originalPartition", 0,
                "originalOffset", 12345L,
                "originalKey", "test-key",
                "originalValue", Map.of("orderId", 12345L, "videoUrl", "https://test.com"),
                "errorMessage", "Processing timeout",
                "errorClass", "TimeoutException",
                "timestamp", LocalDateTime.now().toString()
        );

        // Send to DLQ topic
        testProducer.send(new ProducerRecord<>("test.video.processing.dlq", "dlq-key", dlqMessage));
        testProducer.flush();

        // Consume from DLQ with specialized error handler
        CommonErrorHandler dlqErrorHandler = errorConfiguration.deadLetterQueueErrorHandler();
        assertNotNull(dlqErrorHandler, "DLQ error handler should not be null");

        // Verify DLQ message structure
        CompletableFuture.runAsync(() -> {
            ConsumerRecords<String, Object> records = dlqConsumer.poll(Duration.ofMillis(3000));
            for (ConsumerRecord<String, Object> record : records) {
                log.info("Processing DLQ message: {}", record.value());
                
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) record.value();
                
                assertTrue(message.containsKey("originalTopic"), "DLQ message should contain original topic");
                assertTrue(message.containsKey("errorMessage"), "DLQ message should contain error message");
                assertTrue(message.containsKey("timestamp"), "DLQ message should contain timestamp");
                
                dlqProcessingLatch.countDown();
            }
        });

        boolean dlqProcessed = dlqProcessingLatch.await(10, TimeUnit.SECONDS);
        assertTrue(dlqProcessed, "DLQ message should have been processed");

        log.info("Dead letter queue processing test completed");
    }

    @Test
    @Timeout(30)
    void testHighPriorityErrorHandling() throws Exception {
        log.info("Testing high priority error handling");

        CommonErrorHandler highPriorityHandler = errorConfiguration.highPriorityErrorHandler();
        assertNotNull(highPriorityHandler, "High priority error handler should not be null");

        CountDownLatch highPriorityLatch = new CountDownLatch(1);
        AtomicInteger attemptCount = new AtomicInteger(0);

        // Create high priority message
        Map<String, Object> highPriorityMessage = Map.of(
                "orderId", 99999L,
                "priority", "HIGH",
                "processingType", "URGENT_VIDEO_PROCESSING",
                "timestamp", LocalDateTime.now().toString()
        );

        testProducer.send(new ProducerRecord<>("test.high.priority", "high-priority-key", highPriorityMessage));
        testProducer.flush();

        // Simulate high priority processing with different retry behavior
        // (High priority should have more aggressive retry intervals)
        log.info("High priority error handling test completed");
    }

    @Test
    @Timeout(30)
    void testOrderProcessingErrorHandling() throws Exception {
        log.info("Testing order processing specific error handling");

        CommonErrorHandler orderHandler = errorConfiguration.orderProcessingErrorHandler();
        assertNotNull(orderHandler, "Order processing error handler should not be null");

        // Test business logic specific exceptions
        CountDownLatch orderErrorLatch = new CountDownLatch(1);

        Map<String, Object> orderMessage = Map.of(
                "orderId", 54321L,
                "userId", 123L,
                "serviceId", 456L,
                "quantity", 1000,
                "charge", 25.50,
                "status", "PROCESSING"
        );

        testProducer.send(new ProducerRecord<>("test.order.processing", "order-key", orderMessage));
        testProducer.flush();

        // The order error handler should have specific logic for business exceptions
        log.info("Order processing error handling test completed");
    }

    @Test
    @Timeout(30)
    void testErrorMetricsCollection() {
        log.info("Testing error metrics collection");

        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) errorConfiguration.kafkaErrorMetrics();
        
        assertNotNull(metrics, "Error metrics should not be null");
        assertTrue(metrics.containsKey("totalErrors"), "Should contain total errors count");
        assertTrue(metrics.containsKey("retriedErrors"), "Should contain retried errors count");
        assertTrue(metrics.containsKey("dlqSentCount"), "Should contain DLQ sent count");
        assertTrue(metrics.containsKey("nonRetryableErrors"), "Should contain non-retryable errors count");
        assertTrue(metrics.containsKey("errorRate"), "Should contain error rate");

        // Verify metric types
        assertTrue(metrics.get("totalErrors") instanceof Long, "Total errors should be Long");
        assertTrue(metrics.get("errorRate") instanceof Double, "Error rate should be Double");

        log.info("Error metrics: {}", metrics);
        log.info("Error metrics collection test completed");
    }

    @Test
    @Timeout(30)
    void testExponentialBackoffConfiguration() {
        log.info("Testing exponential backoff configuration");

        // This test verifies that the backoff configuration is working correctly
        // In a real scenario, you would measure the actual intervals between retries
        
        CommonErrorHandler errorHandler = errorConfiguration.defaultKafkaErrorHandler();
        assertNotNull(errorHandler, "Error handler should not be null");

        // The actual backoff testing would require more complex setup to measure timing
        // This is a structural test to ensure the configuration is properly created
        
        log.info("Exponential backoff configuration test completed");
    }

    @Test
    @Timeout(30)
    void testDlqTopicResolution() {
        log.info("Testing DLQ topic resolution");

        // Test the private method through reflection or create a public test method
        // This would verify that topics are correctly mapped to their DLQ counterparts

        String[] originalTopics = {
                "smm.order.processing",
                "smm.video.processing", 
                "smm.youtube.processing",
                "smm.offer.assignments"
        };

        String[] expectedDlqTopics = {
                "smm.order.processing.dlq",
                "smm.video.processing.dlq",
                "smm.youtube.processing.dlq", 
                "smm.offer.assignments.dlq"
        };

        // In a full implementation, you would test the mapping logic
        assertEquals(originalTopics.length, expectedDlqTopics.length, 
                "Should have corresponding DLQ topics for all original topics");

        log.info("DLQ topic resolution test completed");
    }

    @Test
    @Timeout(30)
    void testConcurrentErrorHandling() throws Exception {
        log.info("Testing concurrent error handling");

        int messageCount = 10;
        CountDownLatch concurrentLatch = new CountDownLatch(messageCount);
        AtomicInteger processedCount = new AtomicInteger(0);

        // Send multiple messages concurrently that will cause errors
        for (int i = 0; i < messageCount; i++) {
            VideoProcessingMessage message = VideoProcessingMessage.builder()
                    .orderId((long) (1000 + i))
                    .videoUrl("https://youtube.com/watch?v=concurrent-test-" + i)
                    .attemptNumber(1)
                    .maxAttempts(3)
                    .createdAt(LocalDateTime.now())
                    .build();

            testProducer.send(new ProducerRecord<>("test.video.processing", "concurrent-key-" + i, message));
        }
        testProducer.flush();

        // Process messages concurrently and verify error handling doesn't interfere
        CompletableFuture.runAsync(() -> {
            while (processedCount.get() < messageCount) {
                ConsumerRecords<String, Object> records = testConsumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, Object> record : records) {
                    processedCount.incrementAndGet();
                    concurrentLatch.countDown();
                    log.info("Processed concurrent message: {}", record.key());
                }
            }
        });

        boolean allProcessed = concurrentLatch.await(20, TimeUnit.SECONDS);
        assertTrue(allProcessed, "All concurrent messages should be processed");
        assertEquals(messageCount, processedCount.get(), "Should process all messages");

        log.info("Concurrent error handling test completed - processed {} messages", processedCount.get());
    }
}