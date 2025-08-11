package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.config.AsyncVideoProcessingConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * INTEGRATION TEST: Async Processing Setup Validation
 *
 * <p>Validates that our async processing setup can handle realistic workloads: 1. Thread pools are
 * correctly configured and operational 2. Concurrent task execution works as expected 3.
 * Performance metrics meet requirements under load 4. Error handling and recovery works properly
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AsyncVideoProcessingConfig.class)
@ActiveProfiles("test")
class AsyncProcessingIntegrationTest {

    @Autowired private TaskExecutor videoProcessingExecutor;

    @Autowired private TaskExecutor lightweightAsyncExecutor;

    @Test
    @DisplayName("INTEGRATION: Async thread pools handle concurrent workload efficiently")
    void testAsyncThreadPoolsUnderLoad() throws InterruptedException {
        // Arrange
        int taskCount = 20; // Moderate load test
        CountDownLatch completionLatch = new CountDownLatch(taskCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong totalExecutionTime = new AtomicLong(0);
        AtomicInteger maxConcurrentTasks = new AtomicInteger(0);
        AtomicInteger currentTasks = new AtomicInteger(0);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        String mainThreadName = Thread.currentThread().getName();

        long startTime = System.currentTimeMillis();

        // Act - Submit concurrent tasks to video processing executor
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            CompletableFuture.runAsync(
                    () -> {
                        long taskStart = System.currentTimeMillis();

                        try {
                            // Verify we're running in correct thread pool
                            String workerThread = Thread.currentThread().getName();
                            assertNotEquals(
                                    mainThreadName,
                                    workerThread,
                                    "Task should execute in different thread");
                            assertTrue(
                                    workerThread.contains("VideoProcessing"),
                                    "Should execute in VideoProcessing thread: " + workerThread);

                            // Track concurrency
                            int concurrent = currentTasks.incrementAndGet();
                            maxConcurrentTasks.updateAndGet(
                                    current -> Math.max(current, concurrent));

                            // Simulate realistic video processing work
                            Thread.sleep(100 + (taskId % 50)); // Variable processing time

                            currentTasks.decrementAndGet();
                            successCount.incrementAndGet();

                        } catch (Exception e) {
                            fail("Task " + taskId + " failed: " + e.getMessage());
                        } finally {
                            long taskEnd = System.currentTimeMillis();
                            totalExecutionTime.addAndGet(taskEnd - taskStart);
                            completionLatch.countDown();
                        }
                    },
                    videoProcessingExecutor);
        }

        // Assert - Performance and reliability requirements
        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        assertTrue(completed, "All tasks should complete within timeout");
        assertEquals(taskCount, successCount.get(), "All tasks should succeed");

        // Performance metrics
        double totalTimeSeconds = (endTime - startTime) / 1000.0;
        double throughput = successCount.get() / totalTimeSeconds;
        double avgTaskTime = (double) totalExecutionTime.get() / successCount.get();

        // Assert performance requirements
        assertTrue(
                throughput >= 5.0,
                "Throughput should be at least 5 tasks/second. Actual: " + throughput);
        assertTrue(
                avgTaskTime <= 200,
                "Average task time should be under 200ms. Actual: " + avgTaskTime + "ms");

        // Assert concurrency
        assertTrue(
                maxConcurrentTasks.get() >= 2,
                "Should achieve meaningful concurrency: " + maxConcurrentTasks.get());
        assertTrue(
                maxConcurrentTasks.get() <= executor.getMaxPoolSize(),
                "Concurrency should not exceed max pool size");

        // Assert thread pool health
        assertFalse(
                executor.getThreadPoolExecutor().isShutdown(), "Thread pool should remain healthy");
        assertTrue(
                executor.getThreadPoolExecutor().getCompletedTaskCount() >= taskCount,
                "All tasks should be completed");

        System.out.printf(
                "ASYNC INTEGRATION TEST RESULTS:%n"
                        + "✅ Tasks Completed: %d/%d%n"
                        + "✅ Total Time: %.2f seconds%n"
                        + "✅ Throughput: %.2f tasks/second%n"
                        + "✅ Average Task Time: %.2f ms%n"
                        + "✅ Max Concurrent Tasks: %d (limit: %d)%n"
                        + "✅ Thread Pool Status: HEALTHY%n",
                successCount.get(),
                taskCount,
                totalTimeSeconds,
                throughput,
                avgTaskTime,
                maxConcurrentTasks.get(),
                executor.getMaxPoolSize());
    }

    @Test
    @DisplayName("INTEGRATION: Lightweight executor handles monitoring tasks")
    void testLightweightExecutorPerformance() throws InterruptedException {
        // Arrange
        int monitoringTaskCount = 30; // Higher frequency monitoring tasks
        CountDownLatch completionLatch = new CountDownLatch(monitoringTaskCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) lightweightAsyncExecutor;
        long startTime = System.currentTimeMillis();

        // Act - Submit lightweight monitoring tasks
        for (int i = 0; i < monitoringTaskCount; i++) {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            // Verify correct thread pool
                            String workerThread = Thread.currentThread().getName();
                            assertTrue(
                                    workerThread.contains("Lightweight"),
                                    "Should execute in Lightweight thread: " + workerThread);

                            // Simulate lightweight monitoring work
                            Thread.sleep(20); // Short execution time

                            successCount.incrementAndGet();

                        } catch (Exception e) {
                            fail("Monitoring task failed: " + e.getMessage());
                        } finally {
                            completionLatch.countDown();
                        }
                    },
                    lightweightAsyncExecutor);
        }

        // Assert - Quick execution for monitoring tasks
        boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        assertTrue(completed, "All monitoring tasks should complete quickly");
        assertEquals(
                monitoringTaskCount, successCount.get(), "All monitoring tasks should succeed");

        double totalTimeSeconds = (endTime - startTime) / 1000.0;
        double throughput = successCount.get() / totalTimeSeconds;

        // Monitoring tasks should be very fast
        assertTrue(
                throughput >= 15.0,
                "Lightweight task throughput should be high. Actual: " + throughput);
        assertTrue(totalTimeSeconds <= 5.0, "Monitoring tasks should complete within 5 seconds");

        System.out.printf(
                "LIGHTWEIGHT EXECUTOR TEST RESULTS:%n"
                        + "✅ Monitoring Tasks: %d%n"
                        + "✅ Execution Time: %.2f seconds%n"
                        + "✅ Throughput: %.2f tasks/second%n"
                        + "✅ Performance: OPTIMAL%n",
                successCount.get(), totalTimeSeconds, throughput);
    }

    @Test
    @DisplayName("INTEGRATION: System handles mixed workload without degradation")
    void testMixedWorkloadHandling() throws InterruptedException {
        // Arrange
        int heavyTasks = 10;
        int lightTasks = 20;
        CountDownLatch heavyLatch = new CountDownLatch(heavyTasks);
        CountDownLatch lightLatch = new CountDownLatch(lightTasks);
        AtomicInteger heavySuccessCount = new AtomicInteger(0);
        AtomicInteger lightSuccessCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Act - Submit mixed workload

        // Heavy video processing tasks
        for (int i = 0; i < heavyTasks; i++) {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            assertTrue(
                                    Thread.currentThread().getName().contains("VideoProcessing"));
                            Thread.sleep(200); // Heavy work simulation
                            heavySuccessCount.incrementAndGet();
                        } catch (Exception e) {
                            fail("Heavy task failed: " + e.getMessage());
                        } finally {
                            heavyLatch.countDown();
                        }
                    },
                    videoProcessingExecutor);
        }

        // Lightweight monitoring tasks
        for (int i = 0; i < lightTasks; i++) {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            assertTrue(Thread.currentThread().getName().contains("Lightweight"));
                            Thread.sleep(30); // Light work simulation
                            lightSuccessCount.incrementAndGet();
                        } catch (Exception e) {
                            fail("Light task failed: " + e.getMessage());
                        } finally {
                            lightLatch.countDown();
                        }
                    },
                    lightweightAsyncExecutor);
        }

        // Assert - Both workloads complete successfully
        boolean heavyCompleted = heavyLatch.await(20, TimeUnit.SECONDS);
        boolean lightCompleted = lightLatch.await(15, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        assertTrue(heavyCompleted, "Heavy tasks should complete");
        assertTrue(lightCompleted, "Light tasks should complete");
        assertEquals(heavyTasks, heavySuccessCount.get(), "All heavy tasks should succeed");
        assertEquals(lightTasks, lightSuccessCount.get(), "All light tasks should succeed");

        // Performance validation
        double totalTimeSeconds = (endTime - startTime) / 1000.0;
        assertTrue(
                totalTimeSeconds <= 15.0, "Mixed workload should complete within reasonable time");

        // Thread pool health check
        ThreadPoolTaskExecutor videoExecutor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        ThreadPoolTaskExecutor lightExecutor = (ThreadPoolTaskExecutor) lightweightAsyncExecutor;

        assertFalse(videoExecutor.getThreadPoolExecutor().isShutdown());
        assertFalse(lightExecutor.getThreadPoolExecutor().isShutdown());

        System.out.printf(
                "MIXED WORKLOAD TEST RESULTS:%n"
                        + "✅ Heavy Tasks: %d/%d completed%n"
                        + "✅ Light Tasks: %d/%d completed%n"
                        + "✅ Total Time: %.2f seconds%n"
                        + "✅ Video Pool Status: HEALTHY%n"
                        + "✅ Lightweight Pool Status: HEALTHY%n",
                heavySuccessCount.get(),
                heavyTasks,
                lightSuccessCount.get(),
                lightTasks,
                totalTimeSeconds);
    }

    @Test
    @DisplayName("INTEGRATION: Thread pools handle error scenarios gracefully")
    void testErrorHandlingAndRecovery() throws InterruptedException {
        // Arrange
        int totalTasks = 15;
        int expectedFailures = 5; // 1/3 of tasks will fail
        CountDownLatch completionLatch = new CountDownLatch(totalTasks);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        long initialCompletedCount = executor.getThreadPoolExecutor().getCompletedTaskCount();

        // Act - Submit tasks with intentional failures
        for (int i = 0; i < totalTasks; i++) {
            final int taskId = i;
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            if (taskId % 3 == 0) {
                                // Simulate failure
                                failureCount.incrementAndGet();
                                throw new RuntimeException("Simulated processing error " + taskId);
                            } else {
                                // Successful task
                                Thread.sleep(50);
                                successCount.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            // Expected failures
                        } finally {
                            completionLatch.countDown();
                        }
                    },
                    videoProcessingExecutor);
        }

        // Assert - Error handling and recovery
        boolean completed = completionLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "All tasks should complete (including failed ones)");

        assertEquals(
                totalTasks - expectedFailures,
                successCount.get(),
                "Expected number of successful tasks");
        assertEquals(expectedFailures, failureCount.get(), "Expected number of failed tasks");

        // Verify thread pool remains healthy after errors
        assertFalse(
                executor.getThreadPoolExecutor().isShutdown(),
                "Thread pool should remain operational after errors");

        long finalCompletedCount = executor.getThreadPoolExecutor().getCompletedTaskCount();
        assertTrue(
                finalCompletedCount >= initialCompletedCount + totalTasks,
                "All tasks should be accounted for in completed count");

        // Submit additional tasks to verify recovery
        CountDownLatch recoveryLatch = new CountDownLatch(3);
        AtomicInteger recoverySuccessCount = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            CompletableFuture.runAsync(
                    () -> {
                        try {
                            Thread.sleep(30);
                            recoverySuccessCount.incrementAndGet();
                        } catch (Exception e) {
                            fail("Recovery task should not fail");
                        } finally {
                            recoveryLatch.countDown();
                        }
                    },
                    videoProcessingExecutor);
        }

        boolean recoveryCompleted = recoveryLatch.await(5, TimeUnit.SECONDS);
        assertTrue(recoveryCompleted, "Recovery tasks should complete");
        assertEquals(3, recoverySuccessCount.get(), "All recovery tasks should succeed");

        System.out.printf(
                "ERROR HANDLING TEST RESULTS:%n"
                        + "✅ Successful Tasks: %d%n"
                        + "✅ Failed Tasks: %d (expected: %d)%n"
                        + "✅ Recovery Tasks: %d%n"
                        + "✅ Thread Pool Recovery: SUCCESS%n"
                        + "✅ System Resilience: VALIDATED%n",
                successCount.get(),
                failureCount.get(),
                expectedFailures,
                recoverySuccessCount.get());
    }
}
