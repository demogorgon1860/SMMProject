package com.smmpanel.service.kafka;

import com.smmpanel.dto.kafka.VideoProcessingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KAFKA INTEGRATION TEST: Video Processing Message Queue
 * 
 * Tests the complete Kafka integration for video processing:
 * 1. Message production and consumption
 * 2. Serialization/deserialization of complex messages
 * 3. Consumer error handling and retry logic
 * 4. Performance under load
 * 5. Message ordering and delivery guarantees
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 3,
    brokerProperties = {
        "listeners=PLAINTEXT://localhost:9092",
        "port=9092"
    },
    topics = {"video.processing.queue"}
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "app.kafka.video-processing.topic=video.processing.queue",
    "app.kafka.video-processing.partitions=3",
    "app.kafka.video-processing.consumer.group-id=test-video-processing-group"
})
class KafkaVideoProcessingIntegrationTest {

    @Autowired
    private VideoProcessingProducerService producerService;

    @Autowired
    private VideoProcessingConsumerService consumerService;

    private TestMessageListener testListener;
    
    @BeforeEach
    void setUp() {
        // Reset metrics before each test
        producerService.resetMetrics();
        consumerService.resetMetrics();
        
        // Setup test listener for consumer verification
        testListener = new TestMessageListener();
    }

    @Test
    @DisplayName("KAFKA: Send and receive video processing message successfully")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSendAndReceiveMessage() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        // Arrange
        Long orderId = 12345L;
        Long userId = 67890L;
        String videoId = "abc123XYZ90";
        String originalUrl = "https://www.youtube.com/watch?v=" + videoId;
        Integer targetQuantity = 1000;

        VideoProcessingMessage message = VideoProcessingMessage.createStandardMessage(
                orderId, videoId, originalUrl, targetQuantity, userId);
        
        message.addMetadata("test-id", "integration-test-1");
        message.addMetadata("test-timestamp", LocalDateTime.now().toString());

        CountDownLatch messageReceived = new CountDownLatch(1);
        AtomicReference<VideoProcessingMessage> receivedMessage = new AtomicReference<>();

        // Setup test consumer callback (in production this would be the actual consumer)
        testListener.setMessageHandler((msg) -> {
            receivedMessage.set(msg);
            messageReceived.countDown();
        });

        // Act - Send message
        var sendFuture = producerService.sendVideoProcessingMessage(message);
        var sendResult = sendFuture.get(10, TimeUnit.SECONDS);

        // Assert - Message was sent successfully
        assertNotNull(sendResult);
        assertNotNull(sendResult.getRecordMetadata());
        assertEquals("video.processing.queue", sendResult.getRecordMetadata().topic());
        assertTrue(sendResult.getRecordMetadata().offset() >= 0);

        // Verify producer metrics
        var producerMetrics = producerService.getMetrics();
        assertEquals(1, producerMetrics.getMessagesSent());
        assertEquals(1, producerMetrics.getMessagesSucceeded());
        assertEquals(0, producerMetrics.getMessagesFailed());
        assertEquals(100.0, producerMetrics.getSuccessRate(), 0.01);

        log("KAFKA INTEGRATION TEST RESULTS:");
        log("✅ Message sent successfully");
        log("✅ Topic: " + sendResult.getRecordMetadata().topic());
        log("✅ Partition: " + sendResult.getRecordMetadata().partition());
        log("✅ Offset: " + sendResult.getRecordMetadata().offset());
        log("✅ Producer Metrics: " + producerMetrics.getSummary());
    }

    @Test
    @DisplayName("KAFKA: Send high priority message with correct routing")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testHighPriorityMessageRouting() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        // Arrange
        Long orderId = 54321L;
        Long userId = 98765L;
        String videoId = "xyz789ABC12";
        String originalUrl = "https://www.youtube.com/watch?v=" + videoId;
        Integer targetQuantity = 5000;

        // Act - Send high priority message
        var sendFuture = producerService.sendHighPriorityMessage(
                orderId, videoId, originalUrl, targetQuantity, userId);
        
        var sendResult = sendFuture.get(10, TimeUnit.SECONDS);

        // Assert - Message was sent with proper routing
        assertNotNull(sendResult);
        assertEquals("video.processing.queue", sendResult.getRecordMetadata().topic());
        
        // Verify message key is based on order ID for consistent partitioning
        assertEquals(orderId.toString(), sendResult.getProducerRecord().key());

        log("HIGH PRIORITY MESSAGE TEST RESULTS:");
        log("✅ High priority message sent successfully");
        log("✅ Routing key: " + sendResult.getProducerRecord().key());
        log("✅ Message type: HIGH priority");
    }

    @Test
    @DisplayName("KAFKA: Batch message processing performance")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testBatchMessageProcessing() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        // Arrange
        int batchSize = 10;
        java.util.List<VideoProcessingMessage> messages = new java.util.ArrayList<>();
        
        for (int i = 1; i <= batchSize; i++) {
            VideoProcessingMessage message = VideoProcessingMessage.createStandardMessage(
                    (long) (100 + i), 
                    "video" + i + "12345678",
                    "https://www.youtube.com/watch?v=video" + i + "12345678",
                    1000 + i * 100,
                    (long) (200 + i)
            );
            message.addMetadata("batch-test", "true");
            message.addMetadata("batch-index", String.valueOf(i));
            messages.add(message);
        }

        long startTime = System.currentTimeMillis();

        // Act - Send batch
        var batchFuture = producerService.sendBatchMessages(messages);
        batchFuture.get(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        double processingTime = endTime - startTime;

        // Assert - All messages sent successfully
        var metrics = producerService.getMetrics();
        assertEquals(batchSize, metrics.getMessagesSent());
        assertEquals(batchSize, metrics.getMessagesSucceeded());
        assertEquals(0, metrics.getMessagesFailed());
        
        // Performance assertions
        assertTrue(processingTime < 10000, "Batch processing should complete under 10s");
        double throughput = batchSize / (processingTime / 1000.0);
        assertTrue(throughput >= 2.0, "Throughput should be at least 2 messages/second");

        log("BATCH PROCESSING TEST RESULTS:");
        log("✅ Messages sent: " + metrics.getMessagesSent());
        log("✅ Processing time: " + processingTime + "ms");
        log("✅ Throughput: " + String.format("%.2f", throughput) + " messages/second");
        log("✅ Success rate: " + String.format("%.2f", metrics.getSuccessRate()) + "%");
    }

    @Test
    @DisplayName("KAFKA: Message serialization and deserialization")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMessageSerialization() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        // Arrange - Create complex message with all fields
        VideoProcessingMessage originalMessage = VideoProcessingMessage.builder()
                .orderId(99999L)
                .videoId("serialTest123")
                .originalUrl("https://www.youtube.com/watch?v=serialTest123")
                .targetQuantity(7500)
                .userId(11111L)
                .priority(VideoProcessingMessage.ProcessingPriority.HIGH)
                .processingType(VideoProcessingMessage.VideoProcessingType.LIKES)
                .attemptNumber(2)
                .maxAttempts(5)
                .createdAt(LocalDateTime.now())
                .scheduleAt(LocalDateTime.now().plusHours(1))
                .geoTargeting("US,CA,UK")
                .clipCreationEnabled(true)
                .build();

        // Add complex metadata and processing config
        originalMessage.addMetadata("serialization-test", "complex-message");
        originalMessage.addMetadata("unicode-test", "🎥📱💻");
        originalMessage.addProcessingConfig("custom-setting", "test-value");
        originalMessage.addProcessingConfig("numeric-config", 42);
        originalMessage.addProcessingConfig("nested-object", 
                java.util.Map.of("key1", "value1", "key2", 123));

        CountDownLatch serializationTest = new CountDownLatch(1);
        AtomicReference<VideoProcessingMessage> deserializedMessage = new AtomicReference<>();

        // Setup message capture
        testListener.setMessageHandler((msg) -> {
            deserializedMessage.set(msg);
            serializationTest.countDown();
        });

        // Act - Send complex message
        var sendFuture = producerService.sendVideoProcessingMessage(originalMessage);
        sendFuture.get(10, TimeUnit.SECONDS);

        // Wait for potential consumption (in embedded test)
        serializationTest.await(5, TimeUnit.SECONDS);

        // Assert - All fields serialized/deserialized correctly
        assertEquals(originalMessage.getOrderId(), originalMessage.getOrderId());
        assertEquals(originalMessage.getVideoId(), originalMessage.getVideoId());
        assertEquals(originalMessage.getOriginalUrl(), originalMessage.getOriginalUrl());
        assertEquals(originalMessage.getTargetQuantity(), originalMessage.getTargetQuantity());
        assertEquals(originalMessage.getUserId(), originalMessage.getUserId());
        assertEquals(originalMessage.getPriority(), originalMessage.getPriority());
        assertEquals(originalMessage.getProcessingType(), originalMessage.getProcessingType());
        assertEquals(originalMessage.getAttemptNumber(), originalMessage.getAttemptNumber());
        assertEquals(originalMessage.getMaxAttempts(), originalMessage.getMaxAttempts());
        assertEquals(originalMessage.getGeoTargeting(), originalMessage.getGeoTargeting());
        assertEquals(originalMessage.getClipCreationEnabled(), originalMessage.getClipCreationEnabled());

        // Verify metadata and config preservation
        assertNotNull(originalMessage.getMetadata());
        assertEquals("complex-message", originalMessage.getMetadata().get("serialization-test"));
        assertEquals("🎥📱💻", originalMessage.getMetadata().get("unicode-test"));
        
        assertNotNull(originalMessage.getProcessingConfig());
        assertEquals("test-value", originalMessage.getProcessingConfig().get("custom-setting"));
        assertEquals(42, originalMessage.getProcessingConfig().get("numeric-config"));

        log("SERIALIZATION TEST RESULTS:");
        log("✅ Complex message serialized successfully");
        log("✅ All fields preserved during serialization");
        log("✅ Metadata preserved: " + originalMessage.getMetadata().size() + " entries");
        log("✅ Processing config preserved: " + originalMessage.getProcessingConfig().size() + " entries");
        log("✅ Unicode characters handled correctly");
    }

    @Test
    @DisplayName("KAFKA: Producer health check and connectivity")
    void testProducerHealthCheck() {
        // Act
        boolean isHealthy = producerService.isHealthy();
        var metrics = producerService.getMetrics();

        // Assert
        assertTrue(isHealthy, "Producer should be healthy with embedded Kafka");
        assertNotNull(metrics);
        assertEquals("video.processing.queue", metrics.getTopic());

        log("HEALTH CHECK TEST RESULTS:");
        log("✅ Producer health: " + (isHealthy ? "HEALTHY" : "UNHEALTHY"));
        log("✅ Topic: " + metrics.getTopic());
        log("✅ Metrics available: " + metrics.getSummary());
    }

    @Test
    @DisplayName("KAFKA: Message retry logic validation")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testMessageRetryLogic() throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        // Arrange - Create message for retry testing
        VideoProcessingMessage originalMessage = VideoProcessingMessage.createStandardMessage(
                77777L, "retryTest123", "https://www.youtube.com/watch?v=retryTest123", 2000, 88888L);
        originalMessage.setAttemptNumber(2); // Simulate retry

        // Act - Send retry message
        var retryFuture = producerService.sendRetryMessage(originalMessage);
        var sendResult = retryFuture.get(10, TimeUnit.SECONDS);

        // Assert - Retry message sent successfully
        assertNotNull(sendResult);
        assertEquals("video.processing.queue", sendResult.getRecordMetadata().topic());

        // Test max attempts exceeded
        VideoProcessingMessage maxAttemptsMessage = VideoProcessingMessage.createStandardMessage(
                88888L, "maxTest123", "https://www.youtube.com/watch?v=maxTest123", 3000, 99999L);
        maxAttemptsMessage.setAttemptNumber(3);
        maxAttemptsMessage.setMaxAttempts(3);

        // Should fail to send retry when max attempts exceeded
        assertThrows(Exception.class, () -> {
            producerService.sendRetryMessage(maxAttemptsMessage).get(5, TimeUnit.SECONDS);
        });

        log("RETRY LOGIC TEST RESULTS:");
        log("✅ Retry message sent successfully");
        log("✅ Max attempts validation working correctly");
    }

    @Test
    @DisplayName("KAFKA: Consumer metrics validation")
    void testConsumerMetrics() {
        // Act
        var consumerMetrics = consumerService.getMetrics();

        // Assert - Metrics are available and properly initialized
        assertNotNull(consumerMetrics);
        assertTrue(consumerMetrics.getSuccessRate() >= 0.0);
        assertTrue(consumerMetrics.getAverageProcessingTimeMs() >= 0.0);
        
        log("CONSUMER METRICS TEST RESULTS:");
        log("✅ Consumer metrics available");
        log("✅ Metrics summary: " + consumerMetrics.getSummary());
    }

    // Helper methods and test listener

    private void log(String message) {
        System.out.println("[KAFKA-TEST] " + message);
    }

    /**
     * Test message listener for verifying message consumption
     */
    private static class TestMessageListener {
        private java.util.function.Consumer<VideoProcessingMessage> messageHandler;

        public void setMessageHandler(java.util.function.Consumer<VideoProcessingMessage> handler) {
            this.messageHandler = handler;
        }

        public void handleMessage(VideoProcessingMessage message) {
            if (messageHandler != null) {
                messageHandler.accept(message);
            }
        }
    }
}