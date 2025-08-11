package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.dto.binom.BinomIntegrationRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import com.smmpanel.entity.*;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.repository.jpa.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test class for refactored YouTubeAutomationService Verifies proper separation of transactional
 * and async operations
 */
@ExtendWith(MockitoExtension.class)
class YouTubeAutomationServiceRefactoredTest {

    @Mock private VideoProcessingRepository videoProcessingRepository;

    @Mock private YouTubeAccountRepository youTubeAccountRepository;

    @Mock private OrderRepository orderRepository;

    @Mock private SeleniumService seleniumService;

    @Mock private YouTubeService youTubeService;

    @Mock private BinomService binomService;

    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private com.smmpanel.service.kafka.VideoProcessingProducerService
            videoProcessingProducerService;

    @Mock private OrderStateManagementService orderStateManagementService;

    private YouTubeAutomationService youTubeAutomationService;

    private static final Long TEST_ORDER_ID = 123L;
    private static final String TEST_VIDEO_ID = "abc123XYZ90";
    private static final String TEST_VIDEO_URL = "https://www.youtube.com/watch?v=" + TEST_VIDEO_ID;

    @BeforeEach
    void setUp() {
        youTubeAutomationService =
                new YouTubeAutomationService(
                        videoProcessingRepository,
                        youTubeAccountRepository,
                        orderRepository,
                        seleniumService,
                        youTubeService,
                        binomService,
                        kafkaTemplate,
                        videoProcessingProducerService,
                        orderStateManagementService);

        ReflectionTestUtils.setField(youTubeAutomationService, "clipCreationEnabled", true);
        ReflectionTestUtils.setField(youTubeAutomationService, "clipCoefficient", 3.0);
        ReflectionTestUtils.setField(youTubeAutomationService, "clipCreationTimeoutMs", 300000L);
    }

    @Test
    @DisplayName("initializeOrderProcessing should handle valid order correctly")
    void testInitializeOrderProcessing_ValidOrder() {
        // Arrange
        Order order = createTestOrder();
        VideoProcessing videoProcessing = createTestVideoProcessing();

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(videoProcessingRepository.save(any(VideoProcessing.class)))
                .thenReturn(videoProcessing);

        // Act
        OrderProcessingContext context =
                youTubeAutomationService.initializeOrderProcessing(TEST_ORDER_ID);

        // Assert
        assertNotNull(context, "Context should not be null");
        assertEquals(TEST_ORDER_ID, context.getOrderId());
        assertEquals(TEST_VIDEO_ID, context.getVideoId());
        assertEquals(TEST_VIDEO_URL, context.getOrderLink());
        assertEquals(1000, context.getTargetQuantity());
        assertNotNull(context.getVideoProcessingId());

        verify(orderRepository).save(order);
        verify(videoProcessingRepository).save(any(VideoProcessing.class));
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
        assertEquals(TEST_VIDEO_ID, order.getYoutubeVideoId());
    }

    @Test
    @DisplayName("initializeOrderProcessing should return null for non-pending order")
    void testInitializeOrderProcessing_NonPendingOrder() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.PROCESSING);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));

        // Act
        OrderProcessingContext context =
                youTubeAutomationService.initializeOrderProcessing(TEST_ORDER_ID);

        // Assert
        assertNull(context, "Context should be null for non-pending order");
        verify(orderRepository, never()).save(any());
        verify(videoProcessingRepository, never()).save(any());
    }

    @Test
    @DisplayName("initializeOrderProcessing should throw exception for invalid YouTube URL")
    void testInitializeOrderProcessing_InvalidUrl() {
        // Arrange
        Order order = createTestOrder();
        order.setLink("https://invalid-url.com/video/123");

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));

        // Act & Assert
        assertThrows(
                VideoProcessingException.class,
                () -> {
                    youTubeAutomationService.initializeOrderProcessing(TEST_ORDER_ID);
                });
    }

    @Test
    @DisplayName("updateOrderStartCount should update order with start count")
    void testUpdateOrderStartCount() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.PROCESSING);
        int startCount = 5000;

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));

        // Act
        youTubeAutomationService.updateOrderStartCount(TEST_ORDER_ID, startCount);

        // Assert
        assertEquals(startCount, order.getStartCount());
        assertEquals(order.getQuantity(), order.getRemains());
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("createYouTubeClipAsync should create clip successfully")
    void testCreateYouTubeClipAsync_Success() {
        // Arrange
        OrderProcessingContext context = createTestContext();
        YouTubeAccount account = createTestYouTubeAccount();
        String expectedClipUrl = "https://www.youtube.com/watch?v=clipXYZ123";

        when(youTubeAccountRepository.findFirstByStatusAndDailyClipsCountLessThanDailyLimit(
                        YouTubeAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));
        when(seleniumService.createClip(anyString(), eq(account), anyString()))
                .thenReturn(expectedClipUrl);
        when(youTubeAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        // Act
        ClipCreationResult result = youTubeAutomationService.createYouTubeClipAsync(context);

        // Assert
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals(expectedClipUrl, result.getClipUrl());
        assertNull(result.getErrorMessage());

        verify(seleniumService)
                .createClip(
                        eq("https://www.youtube.com/watch?v=" + TEST_VIDEO_ID),
                        eq(account),
                        anyString());
    }

    @Test
    @DisplayName("createYouTubeClipAsync should handle no available accounts")
    void testCreateYouTubeClipAsync_NoAccounts() {
        // Arrange
        OrderProcessingContext context = createTestContext();

        when(youTubeAccountRepository.findFirstByStatusAndDailyClipsCountLessThanDailyLimit(
                        YouTubeAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());

        // Act
        ClipCreationResult result = youTubeAutomationService.createYouTubeClipAsync(context);

        // Assert
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("No available YouTube accounts", result.getErrorMessage());
        assertNull(result.getClipUrl());

        verify(seleniumService, never()).createClip(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("updateAccountUsageTransactional should update account statistics")
    void testUpdateAccountUsageTransactional() {
        // Arrange
        YouTubeAccount account = createTestYouTubeAccount();
        int originalClipsCount = account.getDailyClipsCount();
        long originalTotalClips = account.getTotalClipsCreated();

        when(youTubeAccountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        // Act
        youTubeAutomationService.updateAccountUsageTransactional(account.getId());

        // Assert
        assertEquals(originalClipsCount + 1, account.getDailyClipsCount());
        assertEquals(originalTotalClips + 1, (long) account.getTotalClipsCreated());
        assertNotNull(account.getLastClipDate());
        assertNotNull(account.getUpdatedAt());
        verify(youTubeAccountRepository).save(account);
    }

    @Test
    @DisplayName("updateVideoProcessingWithClip should handle successful clip creation")
    void testUpdateVideoProcessingWithClip_Success() {
        // Arrange
        VideoProcessing videoProcessing = createTestVideoProcessing();
        ClipCreationResult clipResult = ClipCreationResult.success("https://clip-url.com");

        when(videoProcessingRepository.findById(videoProcessing.getId()))
                .thenReturn(Optional.of(videoProcessing));

        // Act
        youTubeAutomationService.updateVideoProcessingWithClip(videoProcessing.getId(), clipResult);

        // Assert
        assertTrue(videoProcessing.isClipCreated());
        assertEquals("https://clip-url.com", videoProcessing.getClipUrl());
        assertEquals("COMPLETED", videoProcessing.getProcessingStatus());
        assertNotNull(videoProcessing.getUpdatedAt());
        verify(videoProcessingRepository).save(videoProcessing);
    }

    @Test
    @DisplayName("updateVideoProcessingWithClip should handle failed clip creation")
    void testUpdateVideoProcessingWithClip_Failed() {
        // Arrange
        VideoProcessing videoProcessing = createTestVideoProcessing();
        ClipCreationResult clipResult = ClipCreationResult.failed("Selenium error");

        when(videoProcessingRepository.findById(videoProcessing.getId()))
                .thenReturn(Optional.of(videoProcessing));

        // Act
        youTubeAutomationService.updateVideoProcessingWithClip(videoProcessing.getId(), clipResult);

        // Assert
        assertFalse(videoProcessing.isClipCreated());
        assertNull(videoProcessing.getClipUrl());
        assertEquals("COMPLETED", videoProcessing.getProcessingStatus());
        assertTrue(videoProcessing.getErrorMessage().contains("Selenium error"));
        verify(videoProcessingRepository).save(videoProcessing);
    }

    @Test
    @DisplayName("createBinomIntegrationAsync should create integration with clip URL")
    void testCreateBinomIntegrationAsync_WithClip() {
        // Arrange
        OrderProcessingContext context = createTestContext();
        ClipCreationResult clipResult = ClipCreationResult.success("https://clip-url.com");
        BinomIntegrationResponse response = createSuccessfulBinomResponse();

        when(binomService.createBinomIntegration(any(BinomIntegrationRequest.class)))
                .thenReturn(response);

        // Act & Assert
        assertDoesNotThrow(
                () -> {
                    youTubeAutomationService.createBinomIntegrationAsync(context, clipResult);
                });

        verify(binomService)
                .createBinomIntegration(
                        argThat(
                                request ->
                                        request.getTargetUrl().equals("https://clip-url.com")
                                                && request.getClipCreated()
                                                && request.getOrderId().equals(TEST_ORDER_ID)));
    }

    @Test
    @DisplayName("createBinomIntegrationAsync should use original URL when clip fails")
    void testCreateBinomIntegrationAsync_WithoutClip() {
        // Arrange
        OrderProcessingContext context = createTestContext();
        ClipCreationResult clipResult = ClipCreationResult.failed("Clip creation failed");
        BinomIntegrationResponse response = createSuccessfulBinomResponse();

        when(binomService.createBinomIntegration(any(BinomIntegrationRequest.class)))
                .thenReturn(response);

        // Act & Assert
        assertDoesNotThrow(
                () -> {
                    youTubeAutomationService.createBinomIntegrationAsync(context, clipResult);
                });

        verify(binomService)
                .createBinomIntegration(
                        argThat(
                                request ->
                                        request.getTargetUrl().equals(TEST_VIDEO_URL)
                                                && !request.getClipCreated()
                                                && request.getOrderId().equals(TEST_ORDER_ID)));
    }

    @Test
    @DisplayName("finalizeOrderProcessing should set order to ACTIVE status")
    void testFinalizeOrderProcessing() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.PROCESSING);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));

        // Act
        youTubeAutomationService.finalizeOrderProcessing(TEST_ORDER_ID);

        // Assert
        assertEquals(OrderStatus.ACTIVE, order.getStatus());
        assertNotNull(order.getUpdatedAt());
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("handleProcessingErrorTransactional should set order to HOLDING")
    void testHandleProcessingErrorTransactional() {
        // Arrange
        Order order = createTestOrder();
        VideoProcessing videoProcessing = createTestVideoProcessing();
        String errorMessage = "Test error message";

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(videoProcessingRepository.findByOrderId(TEST_ORDER_ID))
                .thenReturn(Optional.of(videoProcessing));

        // Act
        youTubeAutomationService.handleProcessingErrorTransactional(TEST_ORDER_ID, errorMessage);

        // Assert
        assertEquals(OrderStatus.HOLDING, order.getStatus());
        assertEquals(errorMessage, order.getErrorMessage());
        assertEquals("FAILED", videoProcessing.getProcessingStatus());
        assertEquals(errorMessage, videoProcessing.getErrorMessage());

        verify(orderRepository).save(order);
        verify(videoProcessingRepository).save(videoProcessing);
    }

    @Test
    @DisplayName("updateOrderProgressTransactional should complete order when views reached")
    void testUpdateOrderProgressTransactional_OrderCompleted() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.ACTIVE);
        order.setStartCount(1000);
        order.setQuantity(500); // Target 500 views
        int currentViews = 1600; // 600 views gained, exceeds target

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));

        // Act
        youTubeAutomationService.updateOrderProgressTransactional(TEST_ORDER_ID, currentViews);

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(0, order.getRemains());
        assertNotNull(order.getUpdatedAt());
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("retryVideoProcessing should increment attempts and reset status")
    void testRetryVideoProcessing() {
        // Arrange
        Order order = createTestOrder();
        VideoProcessing videoProcessing = createTestVideoProcessing();
        videoProcessing.setProcessingAttempts(1);
        videoProcessing.setProcessingStatus("FAILED");

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(videoProcessingRepository.findByOrderId(TEST_ORDER_ID))
                .thenReturn(Optional.of(videoProcessing));

        // Act
        youTubeAutomationService.retryVideoProcessing(TEST_ORDER_ID);

        // Assert
        assertEquals(2, videoProcessing.getProcessingAttempts());
        assertEquals("PENDING", videoProcessing.getProcessingStatus());
        assertNull(videoProcessing.getErrorMessage());
        assertEquals(OrderStatus.PROCESSING, order.getStatus());

        verify(videoProcessingRepository).save(videoProcessing);
        verify(orderRepository).save(order);
        verify(kafkaTemplate).send("smm.youtube.processing", TEST_ORDER_ID);
    }

    // Helper methods

    private Order createTestOrder() {
        Order order = new Order();
        order.setId(TEST_ORDER_ID);
        order.setLink(TEST_VIDEO_URL);
        order.setQuantity(1000);
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private VideoProcessing createTestVideoProcessing() {
        VideoProcessing processing = new VideoProcessing();
        processing.setId(456L);
        processing.setVideoId(TEST_VIDEO_ID);
        processing.setOriginalUrl(TEST_VIDEO_URL);
        processing.setProcessingStatus("PROCESSING");
        processing.setProcessingAttempts(1);
        processing.setClipCreated(false);
        processing.setCreatedAt(LocalDateTime.now());
        processing.setUpdatedAt(LocalDateTime.now());
        return processing;
    }

    private YouTubeAccount createTestYouTubeAccount() {
        YouTubeAccount account = new YouTubeAccount();
        account.setId(789L);
        account.setUsername("testaccount");
        account.setStatus(YouTubeAccountStatus.ACTIVE);
        account.setDailyClipsCount(5);
        account.setTotalClipsCreated(100);
        account.setDailyLimit(10);
        return account;
    }

    private OrderProcessingContext createTestContext() {
        return OrderProcessingContext.builder()
                .orderId(TEST_ORDER_ID)
                .videoId(TEST_VIDEO_ID)
                .orderLink(TEST_VIDEO_URL)
                .targetQuantity(1000)
                .videoProcessingId(456L)
                .build();
    }

    private BinomIntegrationResponse createSuccessfulBinomResponse() {
        return BinomIntegrationResponse.builder()
                .success(true)
                .campaignIds(java.util.List.of("camp1", "camp2"))
                .totalClicksRequired(1000)
                .build();
    }
}
