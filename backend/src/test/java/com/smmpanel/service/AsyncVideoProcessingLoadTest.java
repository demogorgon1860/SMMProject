package com.smmpanel.service;

import com.smmpanel.config.AsyncVideoProcessingConfig;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import com.smmpanel.exception.VideoProcessingException;
import com.smmpanel.dto.binom.BinomIntegrationRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LOAD TEST: Async Video Processing Under High Volume
 * 
 * Tests the async processing setup with realistic load scenarios:
 * 1. High concurrent order processing (50+ simultaneous orders)
 * 2. Thread pool saturation and recovery
 * 3. Queue management under pressure
 * 4. Resource cleanup and memory management
 * 5. Performance degradation detection
 */
@SpringBootTest(classes = {AsyncVideoProcessingConfig.class})
@ExtendWith(MockitoExtension.class)
@EnableAsync
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "app.async.video-processing.core-pool-size=4",
    "app.async.video-processing.max-pool-size=8", 
    "app.async.video-processing.queue-capacity=20",
    "app.async.video-processing.keep-alive-seconds=10"
})
class AsyncVideoProcessingLoadTest {

    @Autowired
    private TaskExecutor videoProcessingExecutor;

    @Autowired  
    private TaskExecutor lightweightAsyncExecutor;

    @MockBean
    private VideoProcessingRepository videoProcessingRepository;
    
    @MockBean
    private YouTubeAccountRepository youTubeAccountRepository;
    
    @MockBean
    private OrderRepository orderRepository;
    
    @MockBean
    private SeleniumService seleniumService;
    
    @MockBean
    private YouTubeService youTubeService;
    
    @MockBean
    private BinomService binomService;
    
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private com.smmpanel.service.kafka.VideoProcessingProducerService videoProcessingProducerService;
    
    @MockBean
    private OrderStateManagementService orderStateManagementService;

    private YouTubeAutomationService youTubeAutomationService;
    
    private static final int HIGH_LOAD_ORDER_COUNT = 50;
    private static final int EXTREME_LOAD_ORDER_COUNT = 100;
    private static final long LOAD_TEST_TIMEOUT_SECONDS = 60;

    @BeforeEach
    void setUp() {
        youTubeAutomationService = new YouTubeAutomationService(
                videoProcessingRepository,
                youTubeAccountRepository,
                orderRepository,
                seleniumService,
                youTubeService,
                binomService,
                kafkaTemplate,
                videoProcessingProducerService,
                orderStateManagementService
        );
        
        ReflectionTestUtils.setField(youTubeAutomationService, "clipCreationEnabled", true);
        ReflectionTestUtils.setField(youTubeAutomationService, "clipCoefficient", 3.0);
        ReflectionTestUtils.setField(youTubeAutomationService, "clipCreationTimeoutMs", 5000L); // Shorter for testing
    }

    @Test
    @DisplayName("High Load: Process 50 concurrent orders efficiently")
    @Timeout(value = LOAD_TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testHighConcurrentOrderProcessing() throws InterruptedException {
        // Arrange
        setupMocksForLoadTest();
        CountDownLatch completionLatch = new CountDownLatch(HIGH_LOAD_ORDER_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicLong totalProcessingTime = new AtomicLong(0);
        
        // Track thread pool metrics
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        int initialActiveCount = executor.getActiveCount();

        // Act - Submit high volume of orders concurrently
        ExecutorService testExecutor = Executors.newFixedThreadPool(20);
        long startTime = System.currentTimeMillis();
        
        for (int i = 1; i <= HIGH_LOAD_ORDER_COUNT; i++) {
            final Long orderId = (long) i;
            testExecutor.submit(() -> {
                try {
                    long orderStartTime = System.currentTimeMillis();
                    youTubeAutomationService.processYouTubeOrder(orderId);
                    
                    long orderEndTime = System.currentTimeMillis();
                    totalProcessingTime.addAndGet(orderEndTime - orderStartTime);
                    successCount.incrementAndGet();
                    
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    System.err.println("Order " + orderId + " failed: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // Wait for completion
        boolean completed = completionLatch.await(LOAD_TEST_TIMEOUT_SECONDS - 10, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        testExecutor.shutdown();

        // Assert - Performance and reliability metrics
        assertTrue(completed, "All orders should complete within timeout");
        assertTrue(successCount.get() >= HIGH_LOAD_ORDER_COUNT * 0.95, 
                "At least 95% of orders should succeed. Success: " + successCount.get());
        assertTrue(errorCount.get() <= HIGH_LOAD_ORDER_COUNT * 0.05,
                "Error rate should be under 5%. Errors: " + errorCount.get());

        // Performance assertions
        double avgProcessingTime = (double) totalProcessingTime.get() / successCount.get();
        assertTrue(avgProcessingTime < 10000, 
                "Average processing time should be under 10s. Actual: " + avgProcessingTime + "ms");

        double totalTimeSeconds = (endTime - startTime) / 1000.0;
        double throughput = successCount.get() / totalTimeSeconds;
        assertTrue(throughput >= 2.0, 
                "Throughput should be at least 2 orders/second. Actual: " + throughput);

        // Thread pool health check
        int finalActiveCount = executor.getActiveCount();
        assertTrue(finalActiveCount <= executor.getMaxPoolSize(), 
                "Active threads should not exceed max pool size");

        System.out.printf("LOAD TEST RESULTS:%n" +
                "- Total Orders: %d%n" +
                "- Successful: %d%n" +
                "- Failed: %d%n" +
                "- Total Time: %.2fs%n" +
                "- Throughput: %.2f orders/sec%n" +
                "- Avg Processing Time: %.2fms%n" +
                "- Thread Pool: %d/%d active%n",
                HIGH_LOAD_ORDER_COUNT, successCount.get(), errorCount.get(),
                totalTimeSeconds, throughput, avgProcessingTime,
                finalActiveCount, executor.getMaxPoolSize());
    }

    @Test
    @DisplayName("Thread Pool Saturation: Handle queue overflow gracefully")
    @Timeout(value = LOAD_TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testThreadPoolSaturationRecovery() throws InterruptedException {
        // Arrange - Setup slow mock responses to saturate thread pool
        setupSlowMocksForSaturationTest();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        
        CountDownLatch saturationLatch = new CountDownLatch(30); // More than queue + max threads
        AtomicInteger rejectedTasks = new AtomicInteger(0);
        AtomicInteger successfulTasks = new AtomicInteger(0);

        // Act - Overwhelm the thread pool
        ExecutorService testExecutor = Executors.newFixedThreadPool(10);
        
        for (int i = 1; i <= 30; i++) {
            final Long orderId = (long) i;
            testExecutor.submit(() -> {
                try {
                    youTubeAutomationService.processYouTubeOrder(orderId);
                    successfulTasks.incrementAndGet();
                } catch (Exception e) {
                    if (e.getMessage().contains("rejected") || e.getMessage().contains("capacity")) {
                        rejectedTasks.incrementAndGet();
                    }
                } finally {
                    saturationLatch.countDown();
                }
            });
        }

        // Monitor thread pool during saturation
        int maxActiveThreads = 0;
        int maxQueueSize = 0;
        
        for (int i = 0; i < 10; i++) {
            Thread.sleep(200);
            maxActiveThreads = Math.max(maxActiveThreads, executor.getActiveCount());
            maxQueueSize = Math.max(maxQueueSize, executor.getThreadPoolExecutor().getQueue().size());
        }

        boolean completed = saturationLatch.await(LOAD_TEST_TIMEOUT_SECONDS - 20, TimeUnit.SECONDS);
        testExecutor.shutdown();

        // Assert - Graceful degradation
        assertTrue(completed, "Saturation test should complete");
        assertTrue(maxActiveThreads <= executor.getMaxPoolSize(), 
                "Should not exceed max pool size: " + maxActiveThreads);
        assertTrue(maxQueueSize <= 25, // Slightly above configured capacity for buffer
                "Queue size should stay manageable: " + maxQueueSize);

        // System should handle overflow via rejection policy (CALLER_RUNS)
        assertTrue(successfulTasks.get() + rejectedTasks.get() == 30,
                "All tasks should be accounted for");

        System.out.printf("SATURATION TEST RESULTS:%n" +
                "- Max Active Threads: %d (limit: %d)%n" +
                "- Max Queue Size: %d (limit: %d)%n" +
                "- Successful Tasks: %d%n" +
                "- Rejected Tasks: %d%n",
                maxActiveThreads, executor.getMaxPoolSize(),
                maxQueueSize, executor.getQueueCapacity(),
                successfulTasks.get(), rejectedTasks.get());
    }

    @Test
    @DisplayName("Memory Management: Process orders without memory leaks")
    @Timeout(value = LOAD_TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    void testMemoryManagementUnderLoad() throws InterruptedException {
        // Arrange
        setupMocksForLoadTest();
        Runtime runtime = Runtime.getRuntime();
        
        // Force GC and measure baseline
        System.gc();
        Thread.sleep(100);
        long baselineMemory = runtime.totalMemory() - runtime.freeMemory();

        // Act - Process multiple batches to test memory cleanup
        int batchSize = 20;
        int batches = 3;
        
        for (int batch = 0; batch < batches; batch++) {
            CountDownLatch batchLatch = new CountDownLatch(batchSize);
            
            for (int i = 1; i <= batchSize; i++) {
                final Long orderId = (long) (batch * batchSize + i);
                CompletableFuture.runAsync(() -> {
                    try {
                        youTubeAutomationService.processYouTubeOrder(orderId);
                    } catch (Exception e) {
                        // Expected in test environment
                    } finally {
                        batchLatch.countDown();
                    }
                });
            }
            
            batchLatch.await(15, TimeUnit.SECONDS);
            
            // Force cleanup between batches
            System.gc();
            Thread.sleep(500);
        }

        // Final memory check
        System.gc();
        Thread.sleep(100);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - baselineMemory;
        
        // Assert - Memory should not grow excessively
        double memoryIncreasePercent = (double) memoryIncrease / baselineMemory * 100;
        assertTrue(memoryIncreasePercent < 50, 
                "Memory increase should be under 50%. Actual: " + memoryIncreasePercent + "%");

        System.out.printf("MEMORY TEST RESULTS:%n" +
                "- Baseline Memory: %.2f MB%n" +
                "- Final Memory: %.2f MB%n" +
                "- Memory Increase: %.2f MB (%.2f%%)%n" +
                "- Processed: %d orders%n",
                baselineMemory / 1024.0 / 1024.0,
                finalMemory / 1024.0 / 1024.0,
                memoryIncrease / 1024.0 / 1024.0,
                memoryIncreasePercent,
                batchSize * batches);
    }

    @Test
    @DisplayName("Performance Degradation: Detect system overload conditions")
    void testPerformanceDegradationDetection() throws InterruptedException {
        // Arrange
        setupMocksForLoadTest();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        
        List<Long> processingTimes = new ArrayList<>();
        CountDownLatch testLatch = new CountDownLatch(30);
        
        // Act - Submit orders with timing measurement
        for (int i = 1; i <= 30; i++) {
            final Long orderId = (long) i;
            CompletableFuture.runAsync(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    youTubeAutomationService.processYouTubeOrder(orderId);
                    long endTime = System.currentTimeMillis();
                    
                    synchronized (processingTimes) {
                        processingTimes.add(endTime - startTime);
                    }
                } catch (Exception e) {
                    // Expected in test
                } finally {
                    testLatch.countDown();
                }
            });
            
            // Slight delay to create gradual load
            Thread.sleep(50);
        }

        testLatch.await(LOAD_TEST_TIMEOUT_SECONDS - 30, TimeUnit.SECONDS);

        // Assert - Performance metrics
        assertTrue(processingTimes.size() >= 25, 
                "Should have timing data for most orders");

        // Calculate performance metrics
        Collections.sort(processingTimes);
        long medianTime = processingTimes.get(processingTimes.size() / 2);
        long p95Time = processingTimes.get((int) (processingTimes.size() * 0.95));
        double avgTime = processingTimes.stream().mapToLong(Long::longValue).average().orElse(0);

        // Performance assertions
        assertTrue(medianTime < 5000, 
                "Median processing time should be under 5s. Actual: " + medianTime + "ms");
        assertTrue(p95Time < 15000, 
                "95th percentile should be under 15s. Actual: " + p95Time + "ms");
        assertTrue(avgTime < 8000,
                "Average processing time should be under 8s. Actual: " + avgTime + "ms");

        System.out.printf("PERFORMANCE METRICS:%n" +
                "- Median Time: %dms%n" +
                "- Average Time: %.2fms%n" +
                "- 95th Percentile: %dms%n" +
                "- Total Samples: %d%n",
                medianTime, avgTime, p95Time, processingTimes.size());
    }

    @Test
    @DisplayName("Error Recovery: Handle failures without affecting other orders")
    void testErrorRecoveryUnderLoad() throws InterruptedException {
        // Arrange - Mix of successful and failing orders
        setupMixedMocksForErrorTest();
        
        CountDownLatch errorTestLatch = new CountDownLatch(40);
        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger failedOrders = new AtomicInteger(0);
        
        // Act - Submit mix of orders (some will fail intentionally)
        for (int i = 1; i <= 40; i++) {
            final Long orderId = (long) i;
            CompletableFuture.runAsync(() -> {
                try {
                    youTubeAutomationService.processYouTubeOrder(orderId);
                    successfulOrders.incrementAndGet();
                } catch (Exception e) {
                    failedOrders.incrementAndGet();
                } finally {
                    errorTestLatch.countDown();
                }
            });
        }

        boolean completed = errorTestLatch.await(LOAD_TEST_TIMEOUT_SECONDS - 20, TimeUnit.SECONDS);
        
        // Assert - Error isolation
        assertTrue(completed, "Error recovery test should complete");
        assertTrue(successfulOrders.get() > 0, 
                "Some orders should succeed despite errors");
        assertTrue(failedOrders.get() > 0, 
                "Some orders should fail as expected");
        
        // Verify thread pool is still healthy
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        assertFalse(executor.getThreadPoolExecutor().isShutdown(), 
                "Thread pool should remain operational");

        System.out.printf("ERROR RECOVERY RESULTS:%n" +
                "- Successful Orders: %d%n" +
                "- Failed Orders: %d%n" +
                "- Total Processed: %d%n" +
                "- Thread Pool Status: %s%n",
                successfulOrders.get(), failedOrders.get(),
                successfulOrders.get() + failedOrders.get(),
                executor.getThreadPoolExecutor().isShutdown() ? "SHUTDOWN" : "HEALTHY");
    }

    // Helper methods for test setup

    private void setupMocksForLoadTest() {
        // Fast, successful mock responses for load testing
        when(orderRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            return Optional.of(createTestOrder(orderId));
        });
        
        when(videoProcessingRepository.save(any(VideoProcessing.class))).thenAnswer(invocation -> {
            VideoProcessing vp = invocation.getArgument(0);
            vp.setId(ThreadLocalRandom.current().nextLong(1000, 9999));
            return vp;
        });

        when(youTubeService.getVideoViewCount(anyString())).thenReturn(1000);
        
        when(youTubeAccountRepository.findFirstByStatusAndDailyClipsCountLessThanDailyLimit(any()))
                .thenReturn(Optional.of(createTestYouTubeAccount()));
                
        when(seleniumService.createClip(anyString(), any(), anyString()))
                .thenReturn("https://test-clip-url.com");
                
        when(binomService.createBinomIntegration(any())).thenReturn(createSuccessfulBinomResponse());
    }

    private void setupSlowMocksForSaturationTest() {
        // Slow mock responses to cause thread pool saturation
        when(orderRepository.findById(anyLong())).thenAnswer(invocation -> {
            try {
                Thread.sleep(1000); // 1 second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Long orderId = invocation.getArgument(0);
            return Optional.of(createTestOrder(orderId));
        });
        
        when(videoProcessingRepository.save(any(VideoProcessing.class))).thenAnswer(invocation -> {
            VideoProcessing vp = invocation.getArgument(0);
            vp.setId(ThreadLocalRandom.current().nextLong(1000, 9999));
            return vp;
        });

        when(youTubeService.getVideoViewCount(anyString())).thenAnswer(invocation -> {
            try {
                Thread.sleep(500); // Additional delay for external API simulation
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1000;
        });
        
        when(youTubeAccountRepository.findFirstByStatusAndDailyClipsCountLessThanDailyLimit(any()))
                .thenReturn(Optional.of(createTestYouTubeAccount()));
                
        when(seleniumService.createClip(anyString(), any(), anyString())).thenAnswer(invocation -> {
            try {
                Thread.sleep(2000); // Selenium is slow
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "https://test-clip-url.com";
        });
        
        when(binomService.createBinomIntegration(any())).thenReturn(createSuccessfulBinomResponse());
    }

    private void setupMixedMocksForErrorTest() {
        // Mix of successful and failing responses
        when(orderRepository.findById(anyLong())).thenAnswer(invocation -> {
            Long orderId = invocation.getArgument(0);
            if (orderId % 3 == 0) {
                throw new RuntimeException("Simulated database error for order " + orderId);
            }
            return Optional.of(createTestOrder(orderId));
        });
        
        when(videoProcessingRepository.save(any(VideoProcessing.class))).thenAnswer(invocation -> {
            VideoProcessing vp = invocation.getArgument(0);
            vp.setId(ThreadLocalRandom.current().nextLong(1000, 9999));
            return vp;
        });

        when(youTubeService.getVideoViewCount(anyString())).thenAnswer(invocation -> {
            String videoId = invocation.getArgument(0);
            if (videoId.contains("error")) {
                throw new RuntimeException("YouTube API error");
            }
            return 1000;
        });
        
        when(youTubeAccountRepository.findFirstByStatusAndDailyClipsCountLessThanDailyLimit(any()))
                .thenReturn(Optional.of(createTestYouTubeAccount()));
                
        when(seleniumService.createClip(anyString(), any(), anyString())).thenAnswer(invocation -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                throw new RuntimeException("Selenium automation failed");
            }
            return "https://test-clip-url.com";
        });
        
        when(binomService.createBinomIntegration(any())).thenReturn(createSuccessfulBinomResponse());
    }

    private Order createTestOrder(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        // Use proper 11-character YouTube video ID format for validation
        String videoId = String.format("abc%08d", orderId); // Creates abc00000001, abc00000002, etc.
        order.setLink("https://www.youtube.com/watch?v=" + videoId);
        order.setQuantity(1000);
        order.setStatus(OrderStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private YouTubeAccount createTestYouTubeAccount() {
        YouTubeAccount account = new YouTubeAccount();
        account.setId(ThreadLocalRandom.current().nextLong(1, 1000));
        account.setUsername("testaccount");
        account.setStatus(YouTubeAccountStatus.ACTIVE);
        account.setDailyClipsCount(0);
        account.setDailyLimit(10);
        account.setTotalClipsCreated(0);
        return account;
    }

    private BinomIntegrationResponse createSuccessfulBinomResponse() {
        return BinomIntegrationResponse.builder()
                .success(true)
                .campaignIds(List.of("camp1", "camp2"))
                .totalClicksRequired(1000)
                .build();
    }
}