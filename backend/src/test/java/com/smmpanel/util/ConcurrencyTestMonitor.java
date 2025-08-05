package com.smmpanel.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance monitoring utility for concurrency tests.
 * Tracks system resources, thread performance, and test metrics.
 */
@Slf4j
@Component
public class ConcurrencyTestMonitor {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    
    private final ConcurrentHashMap<String, TestMetrics> testMetrics = new ConcurrentHashMap<>();
    private final AtomicLong startTime = new AtomicLong();
    private final AtomicInteger activeTests = new AtomicInteger(0);

    public void startTest(String testName) {
        if (startTime.compareAndSet(0, System.currentTimeMillis())) {
            logSystemStatus("Test Suite Started");
        }
        
        activeTests.incrementAndGet();
        testMetrics.put(testName, new TestMetrics(testName));
        
        log.info("🚀 Starting test: {} (Active tests: {})", testName, activeTests.get());
        logResourceUsage();
    }

    public void endTest(String testName) {
        TestMetrics metrics = testMetrics.get(testName);
        if (metrics != null) {
            metrics.endTest();
            log.info("✅ Completed test: {} in {}ms", testName, metrics.getDuration());
        }
        
        int remaining = activeTests.decrementAndGet();
        if (remaining == 0) {
            generateFinalReport();
        }
        
        logResourceUsage();
    }

    public void recordOperation(String testName, String operationType, boolean success, long durationMs) {
        TestMetrics metrics = testMetrics.get(testName);
        if (metrics != null) {
            metrics.recordOperation(operationType, success, durationMs);
        }
    }

    public void logResourceUsage() {
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024); // MB
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024); // MB
        int threadCount = threadBean.getThreadCount();
        
        log.debug("📊 Resources - Memory: {}/{} MB ({:.1f}%), Threads: {}", 
            usedMemory, maxMemory, (double) usedMemory / maxMemory * 100, threadCount);
    }

    public void logSystemStatus(String phase) {
        log.info("=== {} ===", phase);
        log.info("System Memory:");
        log.info("  Heap Used: {} MB", memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024));
        log.info("  Heap Max: {} MB", memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024));
        log.info("  Non-Heap Used: {} MB", memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024));
        
        log.info("Thread Information:");
        log.info("  Current Threads: {}", threadBean.getThreadCount());
        log.info("  Peak Threads: {}", threadBean.getPeakThreadCount());
        log.info("  Daemon Threads: {}", threadBean.getDaemonThreadCount());
        
        if (threadBean.isCurrentThreadCpuTimeSupported()) {
            log.info("  CPU Time: {} ms", threadBean.getCurrentThreadCpuTime() / 1_000_000);
        }
        
        log.info("===================");
    }

    private void generateFinalReport() {
        long totalDuration = System.currentTimeMillis() - startTime.get();
        
        log.info("🎯 CONCURRENCY TEST SUITE FINAL REPORT");
        log.info("=====================================");
        log.info("Total Suite Duration: {} ms ({:.2f} minutes)", totalDuration, totalDuration / 60000.0);
        log.info("Total Tests Executed: {}", testMetrics.size());
        
        // Overall statistics
        long totalOperations = 0;
        long totalSuccessful = 0;
        long totalFailed = 0;
        long totalOperationTime = 0;
        long maxTestDuration = 0;
        String slowestTest = "";
        
        for (TestMetrics metrics : testMetrics.values()) {
            totalOperations += metrics.getTotalOperations();
            totalSuccessful += metrics.getSuccessfulOperations();
            totalFailed += metrics.getFailedOperations();
            totalOperationTime += metrics.getTotalOperationTime();
            
            if (metrics.getDuration() > maxTestDuration) {
                maxTestDuration = metrics.getDuration();
                slowestTest = metrics.getTestName();
            }
        }
        
        log.info("Overall Performance:");
        log.info("  Total Operations: {}", totalOperations);
        log.info("  Successful: {} ({:.2f}%)", totalSuccessful, 
            totalOperations > 0 ? (double) totalSuccessful / totalOperations * 100 : 0);
        log.info("  Failed: {} ({:.2f}%)", totalFailed, 
            totalOperations > 0 ? (double) totalFailed / totalOperations * 100 : 0);
        log.info("  Average Operation Time: {:.2f} ms", 
            totalOperations > 0 ? (double) totalOperationTime / totalOperations : 0);
        
        log.info("Test Performance:");
        log.info("  Slowest Test: {} ({} ms)", slowestTest, maxTestDuration);
        log.info("  Average Test Duration: {:.2f} ms", (double) totalDuration / testMetrics.size());
        
        // Detailed test breakdown
        log.info("Individual Test Results:");
        testMetrics.values().stream()
            .sorted((a, b) -> Long.compare(b.getDuration(), a.getDuration()))
            .forEach(metrics -> {
                log.info("  {} - Duration: {}ms, Operations: {}, Success Rate: {:.2f}%",
                    metrics.getTestName(),
                    metrics.getDuration(),
                    metrics.getTotalOperations(),
                    metrics.getSuccessRate());
            });
        
        // Resource usage summary
        logSystemStatus("Test Suite Completed");
        
        // Performance recommendations
        generatePerformanceRecommendations(totalOperations, totalDuration, maxTestDuration);
        
        log.info("=====================================");
    }

    private void generatePerformanceRecommendations(long totalOperations, long totalDuration, long maxTestDuration) {
        log.info("Performance Analysis & Recommendations:");
        
        double operationsPerSecond = totalOperations / (totalDuration / 1000.0);
        log.info("  Throughput: {:.2f} operations/second", operationsPerSecond);
        
        if (operationsPerSecond < 100) {
            log.warn("  ⚠️  Low throughput detected. Consider optimizing database queries or connection pool.");
        } else if (operationsPerSecond > 500) {
            log.info("  ✅ Excellent throughput performance.");
        } else {
            log.info("  ✅ Good throughput performance.");
        }
        
        if (maxTestDuration > 180000) { // 3 minutes
            log.warn("  ⚠️  Long-running test detected. Consider breaking down complex scenarios.");
        }
        
        long currentMemory = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        double memoryUsagePercent = (double) currentMemory / maxMemory * 100;
        
        if (memoryUsagePercent > 80) {
            log.warn("  ⚠️  High memory usage ({}%). Consider increasing heap size or optimizing object creation.", 
                String.format("%.1f", memoryUsagePercent));
        } else {
            log.info("  ✅ Memory usage within acceptable limits ({:.1f}%).", memoryUsagePercent);
        }
        
        int threadCount = threadBean.getThreadCount();
        if (threadCount > 100) {
            log.warn("  ⚠️  High thread count ({}). Monitor for thread pool exhaustion.", threadCount);
        } else {
            log.info("  ✅ Thread count within normal range ({}).", threadCount);
        }
    }

    /**
     * Internal metrics tracking for individual tests
     */
    private static class TestMetrics {
        private final String testName;
        private final long startTime;
        private long endTime;
        private final ConcurrentHashMap<String, AtomicInteger> operationCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> successCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> operationTimes = new ConcurrentHashMap<>();

        public TestMetrics(String testName) {
            this.testName = testName;
            this.startTime = System.currentTimeMillis();
        }

        public void endTest() {
            this.endTime = System.currentTimeMillis();
        }

        public void recordOperation(String operationType, boolean success, long durationMs) {
            operationCounts.computeIfAbsent(operationType, k -> new AtomicInteger(0)).incrementAndGet();
            if (success) {
                successCounts.computeIfAbsent(operationType, k -> new AtomicInteger(0)).incrementAndGet();
            }
            operationTimes.computeIfAbsent(operationType, k -> new AtomicLong(0)).addAndGet(durationMs);
        }

        public String getTestName() { return testName; }
        public long getDuration() { return endTime - startTime; }
        
        public long getTotalOperations() {
            return operationCounts.values().stream().mapToLong(AtomicInteger::get).sum();
        }
        
        public long getSuccessfulOperations() {
            return successCounts.values().stream().mapToLong(AtomicInteger::get).sum();
        }
        
        public long getFailedOperations() {
            return getTotalOperations() - getSuccessfulOperations();
        }
        
        public long getTotalOperationTime() {
            return operationTimes.values().stream().mapToLong(AtomicLong::get).sum();
        }
        
        public double getSuccessRate() {
            long total = getTotalOperations();
            return total > 0 ? (double) getSuccessfulOperations() / total * 100 : 0;
        }
    }
}