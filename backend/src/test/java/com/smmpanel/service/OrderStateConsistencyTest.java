package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * STATE CONSISTENCY TESTS: Order State Management Under Concurrent Access
 *
 * <p>Tests state consistency across async operations with: 1. Concurrent state transitions
 * validation 2. Race condition detection and prevention 3. State consistency guarantees under high
 * load 4. Processing state cleanup and recovery 5. Thread-safe access to shared state
 */
@ExtendWith(MockitoExtension.class)
class OrderStateConsistencyTest {

    @Mock private OrderRepository orderRepository;

    private OrderStateManagementService orderStateManagementService;

    private static final Long TEST_ORDER_ID = 12345L;
    private static final String TEST_VIDEO_ID = "testVideo123";
    private static final int CONCURRENCY_THREADS = 20;
    private static final int TEST_TIMEOUT_SECONDS = 30;

    @BeforeEach
    void setUp() {
        orderStateManagementService = new OrderStateManagementService(orderRepository);
    }

    @Test
    @DisplayName("Concurrent validation should prevent duplicate processing")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testConcurrentValidationPreventsRaceConditions() throws InterruptedException {
        // Arrange
        Order testOrder = createTestOrder(OrderStatus.PENDING);
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(CONCURRENCY_THREADS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Exception> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Act - Multiple threads try to validate and update the same order simultaneously
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY_THREADS);

        for (int i = 0; i < CONCURRENCY_THREADS; i++) {
            executor.submit(
                    () -> {
                        try {
                            startLatch.await(); // Synchronize start time to maximize race condition
                            // potential

                            OrderValidationResult result =
                                    orderStateManagementService.validateAndUpdateOrderForProcessing(
                                            TEST_ORDER_ID, TEST_VIDEO_ID);

                            if (result.isSuccess()) {
                                successCount.incrementAndGet();
                            } else {
                                failureCount.incrementAndGet();
                            }

                        } catch (Exception e) {
                            exceptions.add(e);
                            failureCount.incrementAndGet();
                        } finally {
                            completionLatch.countDown();
                        }
                    });
        }

        startLatch.countDown(); // Start all threads simultaneously
        boolean completed = completionLatch.await(TEST_TIMEOUT_SECONDS - 5, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Only one thread should succeed in transitioning to PROCESSING
        assertTrue(completed, "All concurrent validation attempts should complete");
        assertEquals(
                1,
                successCount.get(),
                "Exactly one validation should succeed (race condition prevention)");
        assertEquals(
                CONCURRENCY_THREADS - 1,
                failureCount.get(),
                "All other validations should fail due to state changes");

        // Verify final order state
        assertEquals(
                OrderStatus.PROCESSING,
                testOrder.getStatus(),
                "Order should be in PROCESSING state after successful validation");
        assertEquals(
                TEST_VIDEO_ID,
                testOrder.getYoutubeVideoId(),
                "YouTube video ID should be set correctly");

        System.out.printf(
                "CONCURRENT VALIDATION RESULTS:%n"
                        + "- Successful Validations: %d%n"
                        + "- Failed Validations: %d%n"
                        + "- Exceptions: %d%n"
                        + "- Final Order Status: %s%n",
                successCount.get(), failureCount.get(), exceptions.size(), testOrder.getStatus());
    }

    @Test
    @DisplayName("State transitions should be atomic and consistent")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testAtomicStateTransitions() throws InterruptedException {
        // Arrange
        Order testOrder = createTestOrder(OrderStatus.PROCESSING);
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch transitionLatch = new CountDownLatch(10);
        List<StateTransitionResult> results = Collections.synchronizedList(new ArrayList<>());
        List<OrderStatus> observedStates = Collections.synchronizedList(new ArrayList<>());

        // Act - Multiple threads attempt different state transitions
        ExecutorService executor = Executors.newFixedThreadPool(10);

        // Simulate concurrent transitions from PROCESSING to different end states
        for (int i = 0; i < 5; i++) {
            executor.submit(
                    () -> {
                        try {
                            StateTransitionResult result =
                                    orderStateManagementService.transitionToActive(
                                            TEST_ORDER_ID, 1000);
                            results.add(result);
                            observedStates.add(testOrder.getStatus());
                        } catch (Exception e) {
                            // Expected - some transitions will fail due to state changes
                        } finally {
                            transitionLatch.countDown();
                        }
                    });
        }

        for (int i = 0; i < 5; i++) {
            executor.submit(
                    () -> {
                        try {
                            StateTransitionResult result =
                                    orderStateManagementService.transitionToHolding(
                                            TEST_ORDER_ID, "Test transition");
                            results.add(result);
                            observedStates.add(testOrder.getStatus());
                        } catch (Exception e) {
                            // Expected - some transitions will fail
                        } finally {
                            transitionLatch.countDown();
                        }
                    });
        }

        boolean completed = transitionLatch.await(TEST_TIMEOUT_SECONDS - 10, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Transitions should be atomic
        assertTrue(completed, "All transition attempts should complete");

        // Only one type of transition should succeed
        long successfulTransitions = results.stream().mapToLong(r -> r.isSuccess() ? 1 : 0).sum();
        assertEquals(1, successfulTransitions, "Exactly one transition should succeed");

        // Final state should be consistent
        OrderStatus finalStatus = testOrder.getStatus();
        assertTrue(
                finalStatus == OrderStatus.ACTIVE || finalStatus == OrderStatus.HOLDING,
                "Final status should be either ACTIVE or HOLDING: " + finalStatus);

        System.out.printf(
                "ATOMIC TRANSITION RESULTS:%n"
                        + "- Total Transition Attempts: %d%n"
                        + "- Successful Transitions: %d%n"
                        + "- Final Order Status: %s%n"
                        + "- Observed State Changes: %d%n",
                results.size(), successfulTransitions, finalStatus, observedStates.size());
    }

    @Test
    @DisplayName("Processing status updates should be thread-safe")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testThreadSafeProcessingStatusUpdates() throws InterruptedException {
        // Arrange
        Order testOrder = createTestOrder(OrderStatus.PROCESSING);
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Initialize processing state
        orderStateManagementService.validateAndUpdateOrderForProcessing(
                TEST_ORDER_ID, TEST_VIDEO_ID);

        CountDownLatch updateLatch = new CountDownLatch(15);
        AtomicInteger updateCount = new AtomicInteger(0);

        // Act - Multiple threads update processing status simultaneously
        ExecutorService executor = Executors.newFixedThreadPool(15);

        OrderStateManagementService.ProcessingPhase[] phases =
                OrderStateManagementService.ProcessingPhase.values();

        for (int i = 0; i < 15; i++) {
            final int phaseIndex = i % phases.length;
            final String details = "Concurrent update " + i;

            executor.submit(
                    () -> {
                        try {
                            orderStateManagementService.updateProcessingStatus(
                                    TEST_ORDER_ID, phases[phaseIndex], details);
                            updateCount.incrementAndGet();
                        } catch (Exception e) {
                            // Should not happen for valid processing status updates
                            fail("Processing status update should not fail: " + e.getMessage());
                        } finally {
                            updateLatch.countDown();
                        }
                    });
        }

        boolean completed = updateLatch.await(TEST_TIMEOUT_SECONDS - 15, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - All updates should complete successfully
        assertTrue(completed, "All processing status updates should complete");
        assertEquals(15, updateCount.get(), "All processing status updates should succeed");

        // Verify processing state exists and is accessible
        Optional<ProcessingStateInfo> processingState =
                orderStateManagementService.getProcessingState(TEST_ORDER_ID);
        assertTrue(processingState.isPresent(), "Processing state should be maintained");
        assertNotNull(processingState.get().getCurrentPhase(), "Current phase should be set");
        assertNotNull(processingState.get().getLastUpdate(), "Last update time should be set");

        System.out.printf(
                "PROCESSING STATUS UPDATE RESULTS:%n"
                        + "- Total Updates: %d%n"
                        + "- Successful Updates: %d%n"
                        + "- Final Phase: %s%n"
                        + "- Processing State Available: %s%n",
                15,
                updateCount.get(),
                processingState.get().getCurrentPhase(),
                processingState.isPresent());
    }

    @Test
    @DisplayName("Progress updates should handle concurrent view count changes")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testConcurrentProgressUpdates() throws InterruptedException {
        // Arrange
        Order testOrder = createTestOrder(OrderStatus.ACTIVE);
        testOrder.setStartCount(1000);
        testOrder.setQuantity(500); // Need 500 views to complete
        testOrder.setRemains(500);

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch progressLatch = new CountDownLatch(20);
        AtomicInteger completionCount = new AtomicInteger(0);
        List<ProgressUpdateResult> results = Collections.synchronizedList(new ArrayList<>());

        // Act - Multiple threads update progress with different view counts
        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < 20; i++) {
            final int currentViews = 1000 + (i * 50); // Simulate increasing view counts

            executor.submit(
                    () -> {
                        try {
                            ProgressUpdateResult result =
                                    orderStateManagementService.updateOrderProgress(
                                            TEST_ORDER_ID, currentViews);
                            results.add(result);

                            if (result.isCompleted()) {
                                completionCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            fail("Progress update should not fail: " + e.getMessage());
                        } finally {
                            progressLatch.countDown();
                        }
                    });
        }

        boolean completed = progressLatch.await(TEST_TIMEOUT_SECONDS - 10, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Progress updates should be consistent
        assertTrue(completed, "All progress updates should complete");
        assertEquals(20, results.size(), "All progress updates should produce results");

        // Only orders that reached the target should be marked as completed
        long successfulUpdates = results.stream().filter(ProgressUpdateResult::isSuccess).count();
        assertTrue(successfulUpdates > 0, "Some progress updates should succeed");

        // Final state should be consistent
        if (completionCount.get() > 0) {
            assertEquals(
                    OrderStatus.COMPLETED,
                    testOrder.getStatus(),
                    "Order should be COMPLETED if target was reached");
            assertEquals(
                    0, testOrder.getRemains(), "Remaining count should be 0 for completed orders");
        }

        System.out.printf(
                "CONCURRENT PROGRESS UPDATE RESULTS:%n"
                        + "- Total Progress Updates: %d%n"
                        + "- Successful Updates: %d%n"
                        + "- Completion Events: %d%n"
                        + "- Final Status: %s%n"
                        + "- Final Remains: %d%n",
                results.size(),
                successfulUpdates,
                completionCount.get(),
                testOrder.getStatus(),
                testOrder.getRemains());
    }

    @Test
    @DisplayName("Stale processing state cleanup should work under concurrent access")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testStaleProcessingStateCleanup() throws InterruptedException {
        // Arrange - Create multiple orders with processing states
        Map<Long, Order> testOrders = new HashMap<>();
        for (long i = 1; i <= 10; i++) {
            Order order = createTestOrder(OrderStatus.PROCESSING);
            order.setId(i);
            testOrders.put(i, order);
        }

        when(orderRepository.findById(anyLong()))
                .thenAnswer(
                        invocation -> {
                            Long orderId = invocation.getArgument(0);
                            return Optional.ofNullable(testOrders.get(orderId));
                        });
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Initialize processing states for all orders
        for (long i = 1; i <= 10; i++) {
            orderStateManagementService.validateAndUpdateOrderForProcessing(i, TEST_VIDEO_ID + i);
        }

        CountDownLatch cleanupLatch = new CountDownLatch(5);
        AtomicInteger cleanupExecutions = new AtomicInteger(0);

        // Act - Multiple threads attempt cleanup simultaneously
        ExecutorService executor = Executors.newFixedThreadPool(5);

        for (int i = 0; i < 5; i++) {
            executor.submit(
                    () -> {
                        try {
                            // Very short timeout to force stale state detection
                            orderStateManagementService.cleanupStaleProcessingStates(0);
                            cleanupExecutions.incrementAndGet();
                        } catch (Exception e) {
                            // Cleanup should be resilient to concurrent execution
                            System.err.println("Cleanup error: " + e.getMessage());
                        } finally {
                            cleanupLatch.countDown();
                        }
                    });
        }

        boolean completed = cleanupLatch.await(TEST_TIMEOUT_SECONDS - 20, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Cleanup should complete without errors
        assertTrue(completed, "All cleanup operations should complete");
        assertTrue(cleanupExecutions.get() > 0, "At least one cleanup should execute");

        // Verify processing states are cleaned up
        for (long i = 1; i <= 10; i++) {
            Optional<ProcessingStateInfo> state = orderStateManagementService.getProcessingState(i);
            assertFalse(state.isPresent(), "Processing state should be cleaned up for order " + i);
        }

        // Verify orders are transitioned to HOLDING
        for (Order order : testOrders.values()) {
            assertEquals(
                    OrderStatus.HOLDING,
                    order.getStatus(),
                    "Order " + order.getId() + " should be in HOLDING state after cleanup");
            assertNotNull(
                    order.getErrorMessage(), "Order should have error message explaining timeout");
        }

        System.out.printf(
                "CLEANUP TEST RESULTS:%n"
                        + "- Cleanup Executions: %d%n"
                        + "- Orders Processed: %d%n"
                        + "- Orders in HOLDING: %d%n",
                cleanupExecutions.get(),
                testOrders.size(),
                testOrders.values().stream()
                        .mapToInt(o -> o.getStatus() == OrderStatus.HOLDING ? 1 : 0)
                        .sum());
    }

    @Test
    @DisplayName("Invalid state transitions should be consistently rejected")
    @Timeout(value = TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testInvalidStateTransitionRejection() throws InterruptedException {
        // Arrange
        Order testOrder = createTestOrder(OrderStatus.COMPLETED);
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        CountDownLatch rejectionLatch = new CountDownLatch(10);
        AtomicInteger rejectionCount = new AtomicInteger(0);
        List<StateTransitionResult> results = Collections.synchronizedList(new ArrayList<>());

        // Act - Multiple threads attempt invalid transitions from COMPLETED
        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < 10; i++) {
            executor.submit(
                    () -> {
                        try {
                            // Invalid transition: COMPLETED -> ACTIVE
                            StateTransitionResult result =
                                    orderStateManagementService.transitionToActive(
                                            TEST_ORDER_ID, 2000);
                            results.add(result);

                            if (!result.isSuccess()) {
                                rejectionCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // Invalid transitions should be gracefully rejected, not throw
                            // exceptions
                            fail(
                                    "Invalid transition should be rejected gracefully, not throw"
                                            + " exception: "
                                            + e.getMessage());
                        } finally {
                            rejectionLatch.countDown();
                        }
                    });
        }

        boolean completed = rejectionLatch.await(TEST_TIMEOUT_SECONDS - 25, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - All invalid transitions should be rejected
        assertTrue(completed, "All transition attempts should complete");
        assertEquals(10, rejectionCount.get(), "All invalid transitions should be rejected");
        assertEquals(10, results.size(), "All transition attempts should produce results");

        // Order should remain in original state
        assertEquals(
                OrderStatus.COMPLETED,
                testOrder.getStatus(),
                "Order should remain in COMPLETED state");

        // All results should indicate failure with appropriate error messages
        for (StateTransitionResult result : results) {
            assertFalse(result.isSuccess(), "Invalid transition should fail");
            assertNotNull(result.getErrorMessage(), "Failure should have error message");
            assertTrue(
                    result.getErrorMessage().contains("Invalid state transition"),
                    "Error message should explain invalid transition");
        }

        System.out.printf(
                "INVALID TRANSITION REJECTION RESULTS:%n"
                        + "- Total Attempts: %d%n"
                        + "- Rejections: %d%n"
                        + "- Final Status: %s%n"
                        + "- Error Messages Present: %s%n",
                results.size(),
                rejectionCount.get(),
                testOrder.getStatus(),
                results.stream().allMatch(r -> r.getErrorMessage() != null));
    }

    // Helper methods

    private Order createTestOrder(OrderStatus status) {
        Order order = new Order();
        order.setId(TEST_ORDER_ID);
        order.setStatus(status);
        order.setLink("https://www.youtube.com/watch?v=" + TEST_VIDEO_ID);
        order.setQuantity(1000);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }
}
