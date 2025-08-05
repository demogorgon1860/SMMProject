package com.smmpanel.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TEST: Async Video Processing Configuration Validation
 * 
 * Validates that the async processing configuration is working correctly:
 * 1. Thread pool executors are properly configured
 * 2. Tasks execute asynchronously in dedicated thread pools
 * 3. Thread pool sizing and queue management works as expected
 * 4. Health indicators provide accurate metrics
 */
@SpringBootTest(classes = {AsyncVideoProcessingConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.async.video-processing.core-pool-size=2",
    "app.async.video-processing.max-pool-size=4", 
    "app.async.video-processing.queue-capacity=10",
    "app.async.video-processing.keep-alive-seconds=5"
})
class AsyncVideoProcessingConfigTest {

    @Autowired
    private TaskExecutor videoProcessingExecutor;

    @Autowired  
    private TaskExecutor lightweightAsyncExecutor;

    @Autowired
    private VideoProcessingHealthIndicator healthIndicator;

    @Autowired
    private AsyncThreadPoolMonitor threadPoolMonitor;

    @Test
    @DisplayName("Video processing executor should be properly configured")
    void testVideoProcessingExecutorConfiguration() {
        // Assert executor is configured correctly
        assertNotNull(videoProcessingExecutor, "Video processing executor should be initialized");
        assertTrue(videoProcessingExecutor instanceof ThreadPoolTaskExecutor, 
                "Should be ThreadPoolTaskExecutor instance");

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        
        // Verify configuration values
        assertEquals(2, executor.getCorePoolSize(), "Core pool size should match configuration");
        assertEquals(4, executor.getMaxPoolSize(), "Max pool size should match configuration");
        assertEquals(10, executor.getQueueCapacity(), "Queue capacity should match configuration");
        assertTrue(executor.getThreadNamePrefix().contains("VideoProcessing"), 
                "Thread name prefix should contain 'VideoProcessing'");
    }

    @Test
    @DisplayName("Lightweight executor should be properly configured")
    void testLightweightExecutorConfiguration() {
        assertNotNull(lightweightAsyncExecutor, "Lightweight executor should be initialized");
        assertTrue(lightweightAsyncExecutor instanceof ThreadPoolTaskExecutor, 
                "Should be ThreadPoolTaskExecutor instance");

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) lightweightAsyncExecutor;
        
        // Verify it's smaller than main executor
        assertTrue(executor.getCorePoolSize() <= 2, "Lightweight core pool should be smaller");
        assertTrue(executor.getMaxPoolSize() <= 4, "Lightweight max pool should be smaller");
        assertTrue(executor.getThreadNamePrefix().contains("Lightweight"), 
                "Thread name prefix should contain 'Lightweight'");
    }

    @Test
    @DisplayName("Tasks should execute asynchronously in video processing thread pool")
    void testAsyncTaskExecution() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        String mainThreadName = Thread.currentThread().getName();
        
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger executionCount = new AtomicInteger(0);
        
        // Submit multiple tasks to verify async execution
        for (int i = 0; i < 5; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    String workerThreadName = Thread.currentThread().getName();
                    
                    // Verify execution is in different thread
                    assertNotEquals(mainThreadName, workerThreadName, 
                            "Task should execute in different thread");
                    assertTrue(workerThreadName.contains("VideoProcessing"), 
                            "Task should execute in VideoProcessing thread: " + workerThreadName);
                    
                    executionCount.incrementAndGet();
                    
                    // Simulate some work
                    Thread.sleep(100);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }, videoProcessingExecutor);
        }

        // Wait for completion
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All async tasks should complete");
        assertEquals(5, executionCount.get(), "All tasks should have executed");
        
        // Verify thread pool was used
        assertTrue(executor.getThreadPoolExecutor().getTaskCount() >= 5, 
                "Thread pool should have processed tasks");
    }

    @Test
    @DisplayName("Thread pool should handle concurrent task execution")
    void testConcurrentTaskHandling() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        int taskCount = 8; // More than core pool size to test scaling
        
        CountDownLatch startLatch = new CountDownLatch(taskCount);
        CountDownLatch completionLatch = new CountDownLatch(taskCount);
        AtomicInteger maxConcurrentTasks = new AtomicInteger(0);
        AtomicInteger currentTasks = new AtomicInteger(0);
        
        // Submit tasks that run concurrently
        for (int i = 0; i < taskCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    startLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS); // Wait for all tasks to start
                    
                    int concurrent = currentTasks.incrementAndGet();
                    maxConcurrentTasks.updateAndGet(current -> Math.max(current, concurrent));
                    
                    // Simulate work
                    Thread.sleep(200);
                    
                    currentTasks.decrementAndGet();
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            }, videoProcessingExecutor);
        }

        boolean completed = completionLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "All concurrent tasks should complete");
        
        // Verify concurrency
        assertTrue(maxConcurrentTasks.get() >= 2, 
                "Should have achieved some concurrency: " + maxConcurrentTasks.get());
        assertTrue(maxConcurrentTasks.get() <= executor.getMaxPoolSize(), 
                "Concurrency should not exceed max pool size");

        System.out.printf("Concurrent Task Test Results:%n" +
                "- Max Concurrent Tasks: %d%n" +
                "- Core Pool Size: %d%n" +
                "- Max Pool Size: %d%n" +
                "- Tasks Completed: %d%n",
                maxConcurrentTasks.get(), 
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                taskCount);
    }

    @Test
    @DisplayName("Health indicator should provide accurate metrics")
    void testHealthIndicatorMetrics() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        
        // Submit some tasks to generate metrics
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }, videoProcessingExecutor);
        }

        // Check health during execution
        var health = healthIndicator.health();
        assertNotNull(health, "Health check should return result");
        
        // Wait for tasks to complete
        latch.await(5, TimeUnit.SECONDS);
        
        // Check final health
        var finalHealth = healthIndicator.health();
        assertNotNull(finalHealth, "Final health check should return result");
        
        System.out.println("Health Indicator Results:");
        finalHealth.getDetails().forEach((key, value) -> 
            System.out.println("- " + key + ": " + value));
    }

    @Test
    @DisplayName("Thread pool monitor should provide detailed metrics")
    void testThreadPoolMonitorMetrics() throws InterruptedException {
        // Submit tasks to generate metrics
        CountDownLatch latch = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            }, videoProcessingExecutor);
        }

        // Get metrics during execution
        var videoMetrics = threadPoolMonitor.getVideoProcessingMetrics();
        var lightweightMetrics = threadPoolMonitor.getLightweightAsyncMetrics();
        
        assertNotNull(videoMetrics, "Video processing metrics should be available");
        assertNotNull(lightweightMetrics, "Lightweight metrics should be available");
        
        assertEquals("videoProcessing", videoMetrics.getName());
        assertEquals("lightweightAsync", lightweightMetrics.getName());
        
        // Wait for completion
        latch.await(5, TimeUnit.SECONDS);
        
        // Get final metrics
        var finalMetrics = threadPoolMonitor.getVideoProcessingMetrics();
        assertTrue(finalMetrics.getTotalTasks() >= 4, 
                "Should have processed at least 4 tasks");
        
        System.out.printf("Thread Pool Metrics:%n" +
                "- Video Processing: %s%n" +
                "- Lightweight Async: %s%n",
                videoMetrics, lightweightMetrics);
        
        // Test logging (should not throw exception)
        assertDoesNotThrow(() -> threadPoolMonitor.logThreadPoolStats());
    }

    @Test
    @DisplayName("Thread pool should recover from task failures")
    void testTaskFailureRecovery() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        
        CountDownLatch latch = new CountDownLatch(6);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        
        // Submit mix of successful and failing tasks
        for (int i = 0; i < 6; i++) {
            final int taskId = i;
            CompletableFuture.runAsync(() -> {
                try {
                    if (taskId % 2 == 0) {
                        // Successful task
                        Thread.sleep(50);
                        successCount.incrementAndGet();
                    } else {
                        // Failing task
                        failureCount.incrementAndGet();
                        throw new RuntimeException("Simulated failure " + taskId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // Expected failures
                } finally {
                    latch.countDown();
                }
            }, videoProcessingExecutor);
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All tasks should complete (including failures)");
        
        assertEquals(3, successCount.get(), "Should have 3 successful tasks");
        assertEquals(3, failureCount.get(), "Should have 3 failed tasks");
        
        // Verify thread pool is still healthy
        assertFalse(executor.getThreadPoolExecutor().isShutdown(), 
                "Thread pool should remain operational after failures");
        
        long completedTasks = executor.getThreadPoolExecutor().getCompletedTaskCount();
        assertTrue(completedTasks >= 6, 
                "Should have completed all tasks despite failures");

        System.out.printf("Failure Recovery Test Results:%n" +
                "- Successful Tasks: %d%n" +
                "- Failed Tasks: %d%n" +
                "- Total Completed: %d%n" +
                "- Thread Pool Status: %s%n",
                successCount.get(), failureCount.get(), completedTasks,
                executor.getThreadPoolExecutor().isShutdown() ? "SHUTDOWN" : "HEALTHY");
    }
}