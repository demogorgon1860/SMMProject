package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.exception.VideoProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * COMPREHENSIVE ERROR RECOVERY TESTS
 * 
 * Tests all aspects of the error recovery system:
 * 1. Retry mechanism with exponential backoff
 * 2. Dead letter queue management
 * 3. Error tracking and classification
 * 4. Manual retry functionality
 * 5. Statistics and monitoring
 */
@ExtendWith(MockitoExtension.class)
class ErrorRecoveryServiceTest {

    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private OrderStateManagementService orderStateManagementService;

    private ErrorRecoveryService errorRecoveryService;

    private static final Long TEST_ORDER_ID = 123L;
    private static final String TEST_ERROR_TYPE = "VideoProcessingException";
    private static final String TEST_ERROR_MESSAGE = "Failed to process video";
    private static final String TEST_FAILED_PHASE = "VIDEO_ANALYSIS";

    @BeforeEach
    void setUp() {
        errorRecoveryService = new ErrorRecoveryService(orderRepository, orderStateManagementService);
        
        // Set test configuration values
        ReflectionTestUtils.setField(errorRecoveryService, "defaultMaxRetries", 3);
        ReflectionTestUtils.setField(errorRecoveryService, "initialDelayMinutes", 5);
        ReflectionTestUtils.setField(errorRecoveryService, "maxDelayHours", 24);
        ReflectionTestUtils.setField(errorRecoveryService, "backoffMultiplier", 2.0);
    }

    @Test
    @DisplayName("recordErrorAndScheduleRetry should schedule retry for first failure")
    void testRecordErrorAndScheduleRetry_FirstFailure() {
        // Arrange
        Order order = createTestOrder();
        order.setRetryCount(0);
        order.setMaxRetries(3);
        
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        Exception testException = new RuntimeException("Test error");

        // Act
        ErrorRecoveryResult result = errorRecoveryService.recordErrorAndScheduleRetry(
                TEST_ORDER_ID, TEST_ERROR_TYPE, TEST_ERROR_MESSAGE, TEST_FAILED_PHASE, testException);

        // Assert
        assertTrue(result.isSuccess());
        assertEquals(ErrorRecoveryAction.RETRY_SCHEDULED, result.getAction());
        assertEquals(1, result.getRetryCount());
        assertNotNull(result.getNextRetryTime());
        
        // Verify order was updated
        assertEquals(1, order.getRetryCount());
        assertEquals(TEST_ERROR_TYPE, order.getLastErrorType());
        assertEquals(TEST_ERROR_MESSAGE, order.getFailureReason());
        assertEquals(TEST_FAILED_PHASE, order.getFailedPhase());
        assertNotNull(order.getLastRetryAt());
        assertNotNull(order.getNextRetryAt());
        assertNotNull(order.getErrorStackTrace());
        
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("recordErrorAndScheduleRetry should move to DLQ after max retries")
    void testRecordErrorAndScheduleRetry_MaxRetriesExceeded() {
        // Arrange
        Order order = createTestOrder();
        order.setRetryCount(2); // Will become 3 after increment
        order.setMaxRetries(3);
        
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        // Act
        ErrorRecoveryResult result = errorRecoveryService.recordErrorAndScheduleRetry(
                TEST_ORDER_ID, TEST_ERROR_TYPE, TEST_ERROR_MESSAGE, TEST_FAILED_PHASE, null);

        // Assert
        assertTrue(result.isSuccess());
        assertEquals(ErrorRecoveryAction.DEAD_LETTER_QUEUE, result.getAction());
        assertEquals(3, result.getRetryCount());
        
        // Verify order was moved to DLQ
        assertEquals(OrderStatus.HOLDING, order.getStatus());
        assertTrue(order.getIsManuallyFailed());
        assertNull(order.getNextRetryAt());
        
        verify(orderStateManagementService).transitionToHolding(eq(TEST_ORDER_ID), anyString());
    }

    @Test
    @DisplayName("manualRetry should allow operator to retry failed order")
    void testManualRetry_Success() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.HOLDING);
        order.setRetryCount(2);
        order.setIsManuallyFailed(true);
        order.setYoutubeVideoId("testVideoId123");
        
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderStateManagementService.validateAndUpdateOrderForProcessing(eq(TEST_ORDER_ID), anyString()))
                .thenReturn(StateTransitionResult.success(TEST_ORDER_ID, OrderStatus.HOLDING, OrderStatus.PROCESSING));

        String operatorNotes = "Manual retry after fixing external service";

        // Act
        ManualRetryResult result = errorRecoveryService.manualRetry(TEST_ORDER_ID, operatorNotes, true);

        // Assert
        assertTrue(result.isSuccess());
        assertEquals(operatorNotes, result.getOperatorNotes());
        assertTrue(result.isRetryCountReset());
        
        // Verify order was updated
        assertEquals(0, order.getRetryCount()); // Reset as requested
        assertEquals(operatorNotes, order.getOperatorNotes());
        assertFalse(order.getIsManuallyFailed());
        assertNotNull(order.getNextRetryAt());
        
        verify(orderStateManagementService).validateAndUpdateOrderForProcessing(TEST_ORDER_ID, "testVideoId123");
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("manualRetry should fail for invalid order state")
    void testManualRetry_InvalidState() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.COMPLETED); // Cannot retry completed orders
        
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));

        // Act
        ManualRetryResult result = errorRecoveryService.manualRetry(TEST_ORDER_ID, "Test notes", false);

        // Assert
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        
        verify(orderRepository, never()).save(any());
        verify(orderStateManagementService, never()).validateAndUpdateOrderForProcessing(anyLong(), anyString());
    }

    @Test
    @DisplayName("processScheduledRetries should process orders ready for retry")
    void testProcessScheduledRetries() {
        // Arrange
        Order order1 = createTestOrder();
        order1.setId(1L);
        order1.setNextRetryAt(LocalDateTime.now().minusMinutes(5)); // Ready for retry
        
        Order order2 = createTestOrder();
        order2.setId(2L);
        order2.setNextRetryAt(LocalDateTime.now().minusMinutes(1)); // Ready for retry
        
        Page<Order> retryOrders = new PageImpl<>(Arrays.asList(order1, order2));
        
        when(orderRepository.findOrdersReadyForRetry(any(LocalDateTime.class), any()))
                .thenReturn(retryOrders);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(orderStateManagementService.validateAndUpdateOrderForProcessing(anyLong(), anyString()))
                .thenReturn(StateTransitionResult.success(1L, OrderStatus.HOLDING, OrderStatus.PROCESSING));

        // Act
        errorRecoveryService.processScheduledRetries();

        // Assert
        verify(orderRepository).findOrdersReadyForRetry(any(LocalDateTime.class), any());
        verify(orderRepository, times(2)).save(any(Order.class));
        verify(orderStateManagementService, times(2)).validateAndUpdateOrderForProcessing(anyLong(), anyString());
    }

    @Test
    @DisplayName("getErrorStatistics should return comprehensive statistics")
    void testGetErrorStatistics() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        
        when(orderRepository.countFailedOrders()).thenReturn(100L);
        when(orderRepository.countFailedOrdersSince(any())).thenReturn(25L, 75L); // 24h, 1week
        when(orderRepository.countDeadLetterQueueOrders()).thenReturn(10L);
        when(orderRepository.countOrdersPendingRetry(any())).thenReturn(5L);
        
        List<Object[]> errorTypeStats = Arrays.asList(
                new Object[]{"VideoProcessingException", 50L},
                new Object[]{"NetworkException", 30L},
                new Object[]{"TimeoutException", 20L}
        );
        when(orderRepository.getErrorTypeStatistics()).thenReturn(errorTypeStats);

        // Act
        ErrorRecoveryStats stats = errorRecoveryService.getErrorStatistics();

        // Assert
        assertNotNull(stats);
        assertEquals(100L, stats.getTotalFailedOrders());
        assertEquals(25L, stats.getFailedLast24Hours());
        assertEquals(75L, stats.getFailedLastWeek());
        assertEquals(10L, stats.getDeadLetterQueueCount());
        assertEquals(5L, stats.getPendingRetries());
        assertEquals(3, stats.getErrorTypeBreakdown().size());
        
        // Verify error type breakdown
        ErrorTypeStats firstErrorType = stats.getErrorTypeBreakdown().get(0);
        assertEquals("VideoProcessingException", firstErrorType.getErrorType());
        assertEquals(50L, firstErrorType.getCount());
    }

    @Test
    @DisplayName("exponential backoff should calculate correct retry delays")
    void testExponentialBackoffCalculation() {
        // Arrange
        Order order = createTestOrder();
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);

        // Test multiple retry attempts to verify exponential backoff
        LocalDateTime[] retryTimes = new LocalDateTime[3];
        
        for (int i = 0; i < 3; i++) {
            order.setRetryCount(i);
            
            // Act
            ErrorRecoveryResult result = errorRecoveryService.recordErrorAndScheduleRetry(
                    TEST_ORDER_ID, TEST_ERROR_TYPE, TEST_ERROR_MESSAGE, TEST_FAILED_PHASE, null);
            
            // Collect retry times
            retryTimes[i] = order.getNextRetryAt();
        }

        // Assert exponential backoff pattern
        // First retry: ~5 minutes from now
        // Second retry: ~10 minutes from now  
        // Third retry: ~20 minutes from now
        LocalDateTime now = LocalDateTime.now();
        
        assertTrue(retryTimes[0].isAfter(now.plusMinutes(4)));
        assertTrue(retryTimes[0].isBefore(now.plusMinutes(6)));
        
        assertTrue(retryTimes[1].isAfter(now.plusMinutes(9)));
        assertTrue(retryTimes[1].isBefore(now.plusMinutes(11)));
        
        assertTrue(retryTimes[2].isAfter(now.plusMinutes(19)));
        assertTrue(retryTimes[2].isBefore(now.plusMinutes(21)));
    }

    @Test
    @DisplayName("moveToDeadLetterQueue should handle permanent failures correctly")
    void testMoveToDeadLetterQueue() {
        // Arrange
        Order order = createTestOrder();
        order.setRetryCount(3);
        order.setMaxRetries(3);
        
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderStateManagementService.transitionToHolding(eq(TEST_ORDER_ID), anyString()))
                .thenReturn(StateTransitionResult.success(TEST_ORDER_ID, OrderStatus.PROCESSING, OrderStatus.HOLDING));

        String reason = "Max retries exceeded after system errors";

        // Act
        ErrorRecoveryResult result = errorRecoveryService.moveToDeadLetterQueue(order, reason);

        // Assert
        assertTrue(result.isSuccess());
        assertEquals(ErrorRecoveryAction.DEAD_LETTER_QUEUE, result.getAction());
        
        // Verify order was updated correctly
        assertEquals(OrderStatus.HOLDING, order.getStatus());
        assertTrue(order.getIsManuallyFailed());
        assertEquals(reason, order.getFailureReason());
        assertTrue(order.getErrorMessage().contains("DEAD LETTER QUEUE"));
        assertNull(order.getNextRetryAt());
        
        verify(orderStateManagementService).transitionToHolding(TEST_ORDER_ID, "Moved to dead letter queue: " + reason);
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("error recovery should handle missing orders gracefully")
    void testErrorRecovery_OrderNotFound() {
        // Arrange
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(VideoProcessingException.class, () -> {
            errorRecoveryService.recordErrorAndScheduleRetry(
                    TEST_ORDER_ID, TEST_ERROR_TYPE, TEST_ERROR_MESSAGE, TEST_FAILED_PHASE, null);
        });
    }

    @Test
    @DisplayName("manual retry should preserve operator context")
    void testManualRetry_OperatorContext() {
        // Arrange
        Order order = createTestOrder();
        order.setStatus(OrderStatus.HOLDING);
        order.setYoutubeVideoId("testVideoId");
        
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenReturn(order);
        when(orderStateManagementService.validateAndUpdateOrderForProcessing(anyLong(), anyString()))
                .thenReturn(StateTransitionResult.success(TEST_ORDER_ID, OrderStatus.HOLDING, OrderStatus.PROCESSING));

        String operatorNotes = "Retrying after fixing external API configuration issue";

        // Act
        ManualRetryResult result = errorRecoveryService.manualRetry(TEST_ORDER_ID, operatorNotes, false);

        // Assert
        assertTrue(result.isSuccess());
        assertEquals(operatorNotes, order.getOperatorNotes());
        assertFalse(order.getIsManuallyFailed());
        assertNotNull(order.getLastRetryAt());
        
        // Verify retry is scheduled for immediate processing
        assertTrue(order.getNextRetryAt().isBefore(LocalDateTime.now().plusMinutes(2)));
    }

    // Helper methods

    private Order createTestOrder() {
        Order order = new Order();
        order.setId(TEST_ORDER_ID);
        order.setStatus(OrderStatus.PENDING);
        order.setRetryCount(0);
        order.setMaxRetries(3);
        order.setIsManuallyFailed(false);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }
}