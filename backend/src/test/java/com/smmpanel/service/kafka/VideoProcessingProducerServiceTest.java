package com.smmpanel.service.kafka;

import com.smmpanel.dto.kafka.VideoProcessingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UNIT TEST: Video Processing Producer Service
 * 
 * Tests the Kafka producer service functionality:
 * 1. Message sending with proper formatting
 * 2. Error handling and retries
 * 3. Metrics tracking
 * 4. Batch processing
 */
@ExtendWith(MockitoExtension.class)
class VideoProcessingProducerServiceTest {

    @Mock
    private KafkaTemplate<String, VideoProcessingMessage> kafkaTemplate;

    @Mock
    private SendResult<String, VideoProcessingMessage> sendResult;

    private VideoProcessingProducerService producerService;

    private static final String TEST_TOPIC = "video.processing.queue";
    private static final Long TEST_ORDER_ID = 12345L;
    private static final String TEST_VIDEO_ID = "abc123XYZ90";
    private static final String TEST_URL = "https://www.youtube.com/watch?v=" + TEST_VIDEO_ID;
    private static final Integer TEST_QUANTITY = 1000;
    private static final Long TEST_USER_ID = 67890L;

    @BeforeEach
    void setUp() {
        producerService = new VideoProcessingProducerService(kafkaTemplate);
        ReflectionTestUtils.setField(producerService, "videoProcessingTopic", TEST_TOPIC);
        ReflectionTestUtils.setField(producerService, "sendTimeoutMs", 30000L);
    }

    @Test
    @DisplayName("Should send video processing message successfully")
    void testSendVideoProcessingMessageSuccess() {
        // Arrange
        VideoProcessingMessage message = VideoProcessingMessage.createStandardMessage(
                TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendVideoProcessingMessage(message);

        // Assert
        assertNotNull(result);
        assertFalse(result.isCompletedExceptionally());
        
        verify(kafkaTemplate, times(1)).send(any(Message.class));
        
        // Verify metrics
        var metrics = producerService.getMetrics();
        assertEquals(1, metrics.getMessagesSent());
        assertEquals(1, metrics.getMessagesSucceeded());
        assertEquals(0, metrics.getMessagesFailed());
    }

    @Test
    @DisplayName("Should handle send failure correctly")
    void testSendVideoProcessingMessageFailure() {
        // Arrange
        VideoProcessingMessage message = VideoProcessingMessage.createStandardMessage(
                TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Kafka send failed"));
        
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendVideoProcessingMessage(message);

        // Assert
        assertNotNull(result);
        // Note: The actual exception handling happens in the whenComplete callback
        
        verify(kafkaTemplate, times(1)).send(any(Message.class));
        
        // Verify metrics
        var metrics = producerService.getMetrics();
        assertEquals(1, metrics.getMessagesSent());
    }

    @Test
    @DisplayName("Should send standard processing message with correct configuration")
    void testSendStandardProcessingMessage() {
        // Arrange
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendStandardProcessingMessage(
                        TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);

        // Assert
        assertNotNull(result);
        verify(kafkaTemplate, times(1)).send(argThat((Message<VideoProcessingMessage> message) -> {
            VideoProcessingMessage payload = (VideoProcessingMessage) message.getPayload();
            return payload.getOrderId().equals(TEST_ORDER_ID) &&
                   payload.getVideoId().equals(TEST_VIDEO_ID) &&
                   payload.getPriority() == VideoProcessingMessage.ProcessingPriority.MEDIUM;
        }));
    }

    @Test
    @DisplayName("Should send high priority message with correct configuration")
    void testSendHighPriorityMessage() {
        // Arrange
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendHighPriorityMessage(
                        TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);

        // Assert
        assertNotNull(result);
        verify(kafkaTemplate, times(1)).send(argThat((Message<VideoProcessingMessage> message) -> {
            VideoProcessingMessage payload = (VideoProcessingMessage) message.getPayload();
            return payload.getOrderId().equals(TEST_ORDER_ID) &&
                   payload.getVideoId().equals(TEST_VIDEO_ID) &&
                   payload.getPriority() == VideoProcessingMessage.ProcessingPriority.HIGH &&
                   payload.getMaxAttempts() == 5; // High priority gets more retries
        }));
    }

    @Test
    @DisplayName("Should handle retry message correctly")
    void testSendRetryMessage() {
        // Arrange
        VideoProcessingMessage originalMessage = VideoProcessingMessage.createStandardMessage(
                TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);
        originalMessage.setAttemptNumber(1);
        originalMessage.setMaxAttempts(3);
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendRetryMessage(originalMessage);

        // Assert
        assertNotNull(result);
        verify(kafkaTemplate, times(1)).send(argThat((Message<VideoProcessingMessage> message) -> {
            VideoProcessingMessage payload = (VideoProcessingMessage) message.getPayload();
            return payload.getOrderId().equals(TEST_ORDER_ID) &&
                   payload.getAttemptNumber() == 2 && // Incremented
                   payload.getMetadata().containsKey("retry-reason");
        }));
    }

    @Test
    @DisplayName("Should reject retry when max attempts exceeded")
    void testSendRetryMessageMaxAttemptsExceeded() {
        // Arrange
        VideoProcessingMessage originalMessage = VideoProcessingMessage.createStandardMessage(
                TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);
        originalMessage.setAttemptNumber(3);
        originalMessage.setMaxAttempts(3);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendRetryMessage(originalMessage);

        // Assert
        assertNotNull(result);
        assertTrue(result.isCompletedExceptionally());
        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    @DisplayName("Should send delayed message with schedule configuration")
    void testSendDelayedMessage() {
        // Arrange
        LocalDateTime scheduleTime = LocalDateTime.now().plusHours(2);
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendDelayedMessage(
                        TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID, scheduleTime);

        // Assert
        assertNotNull(result);
        verify(kafkaTemplate, times(1)).send(argThat((Message<VideoProcessingMessage> message) -> {
            VideoProcessingMessage payload = (VideoProcessingMessage) message.getPayload();
            return payload.getOrderId().equals(TEST_ORDER_ID) &&
                   payload.getScheduleAt().equals(scheduleTime) &&
                   payload.getMetadata().containsKey("scheduled") &&
                   "true".equals(payload.getMetadata().get("scheduled"));
        }));
    }

    @Test
    @DisplayName("Should send batch messages efficiently")
    void testSendBatchMessages() {
        // Arrange
        List<VideoProcessingMessage> messages = List.of(
                VideoProcessingMessage.createStandardMessage(1L, "video1", "url1", 100, 10L),
                VideoProcessingMessage.createStandardMessage(2L, "video2", "url2", 200, 20L),
                VideoProcessingMessage.createStandardMessage(3L, "video3", "url3", 300, 30L)
        );
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<Void> result = producerService.sendBatchMessages(messages);

        // Assert
        assertNotNull(result);
        verify(kafkaTemplate, times(3)).send(any(Message.class));
        
        // Verify metrics
        var metrics = producerService.getMetrics();
        assertEquals(3, metrics.getMessagesSent());
    }

    @Test
    @DisplayName("Should handle empty batch gracefully")
    void testSendEmptyBatch() {
        // Act
        CompletableFuture<Void> result = producerService.sendBatchMessages(List.of());

        // Assert
        assertNotNull(result);
        assertTrue(result.isDone());
        verify(kafkaTemplate, never()).send(any(Message.class));
    }

    @Test
    @DisplayName("Should send message with custom processing configuration")
    void testSendWithCustomConfig() {
        // Arrange
        java.util.Map<String, Object> processingConfig = java.util.Map.of(
                "custom-setting", "test-value",
                "numeric-setting", 42,
                "boolean-setting", true
        );
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);

        // Act
        CompletableFuture<SendResult<String, VideoProcessingMessage>> result = 
                producerService.sendWithConfig(
                        TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID, processingConfig);

        // Assert
        assertNotNull(result);
        verify(kafkaTemplate, times(1)).send(argThat((Message<VideoProcessingMessage> message) -> {
            VideoProcessingMessage payload = (VideoProcessingMessage) message.getPayload();
            return payload.getProcessingConfig().containsKey("custom-setting") &&
                   payload.getProcessingConfig().get("numeric-setting").equals(42) &&
                   payload.getProcessingConfig().get("boolean-setting").equals(true);
        }));
    }

    @Test
    @DisplayName("Should track metrics correctly")
    void testMetricsTracking() {
        // Arrange
        VideoProcessingMessage message = VideoProcessingMessage.createStandardMessage(
                TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> successFuture = 
                CompletableFuture.completedFuture(sendResult);
        CompletableFuture<SendResult<String, VideoProcessingMessage>> failureFuture = 
                new CompletableFuture<>();
        failureFuture.completeExceptionally(new RuntimeException("Send failed"));
        
        when(kafkaTemplate.send(any(Message.class)))
                .thenReturn(successFuture)
                .thenReturn(failureFuture);

        // Act - Send one success and one failure
        producerService.sendVideoProcessingMessage(message);
        producerService.sendVideoProcessingMessage(message);

        // Assert - Metrics should reflect both attempts
        var metrics = producerService.getMetrics();
        assertEquals(2, metrics.getMessagesSent());
        assertEquals(1, metrics.getMessagesSucceeded());
        assertEquals(0, metrics.getMessagesFailed()); // Failures are tracked in callback
        assertEquals(50.0, metrics.getSuccessRate(), 0.01);
        assertEquals(TEST_TOPIC, metrics.getTopic());
    }

    @Test
    @DisplayName("Should reset metrics correctly")
    void testMetricsReset() {
        // Arrange - Send some messages first
        VideoProcessingMessage message = VideoProcessingMessage.createStandardMessage(
                TEST_ORDER_ID, TEST_VIDEO_ID, TEST_URL, TEST_QUANTITY, TEST_USER_ID);
        
        CompletableFuture<SendResult<String, VideoProcessingMessage>> future = 
                CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(Message.class))).thenReturn(future);
        
        producerService.sendVideoProcessingMessage(message);

        // Act - Reset metrics
        producerService.resetMetrics();

        // Assert - Metrics should be reset to zero
        var metrics = producerService.getMetrics();
        assertEquals(0, metrics.getMessagesSent());
        assertEquals(0, metrics.getMessagesSucceeded());
        assertEquals(0, metrics.getMessagesFailed());
        assertEquals(100.0, metrics.getSuccessRate(), 0.01); // 100% when no messages sent
    }

    @Test
    @DisplayName("Should perform health check correctly")
    void testHealthCheck() {
        // Arrange
        when(kafkaTemplate.partitionsFor(TEST_TOPIC)).thenReturn(List.of());

        // Act
        boolean isHealthy = producerService.isHealthy();

        // Assert
        assertTrue(isHealthy);
        verify(kafkaTemplate, times(1)).partitionsFor(TEST_TOPIC);
    }

    @Test
    @DisplayName("Should handle health check failure")
    void testHealthCheckFailure() {
        // Arrange
        when(kafkaTemplate.partitionsFor(TEST_TOPIC)).thenThrow(new RuntimeException("Kafka unavailable"));

        // Act
        boolean isHealthy = producerService.isHealthy();

        // Assert
        assertFalse(isHealthy);
        verify(kafkaTemplate, times(1)).partitionsFor(TEST_TOPIC);
    }
}