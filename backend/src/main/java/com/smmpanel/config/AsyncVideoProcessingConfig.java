package com.smmpanel.config;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * PRODUCTION-READY Async Configuration for Video Processing
 *
 * <p>FEATURES: 1. Dedicated thread pool for video processing operations 2. System resource-based
 * configuration 3. Custom rejection policies for overload scenarios 4. Thread pool monitoring and
 * health checks 5. Environment-specific tuning
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncVideoProcessingConfig {

    @Value(
            "${app.async.video-processing.core-pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}")
    private int corePoolSize;

    @Value(
            "${app.async.video-processing.max-pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors()"
                + " * 2}}")
    private int maxPoolSize;

    @Value("${app.async.video-processing.queue-capacity:100}")
    private int queueCapacity;

    @Value("${app.async.video-processing.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Value("${app.async.video-processing.thread-name-prefix:VideoProcessing-}")
    private String threadNamePrefix;

    @Value("${app.async.video-processing.await-termination-seconds:60}")
    private int awaitTerminationSeconds;

    @Value("${app.async.video-processing.rejection-policy:CALLER_RUNS}")
    private String rejectionPolicy;

    /**
     * Dedicated executor for video processing operations Handles YouTube API calls, Selenium
     * automation, and Binom integrations
     */
    @Bean("videoProcessingExecutor")
    public TaskExecutor videoProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core configuration
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);

        // Graceful shutdown configuration
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);

        // Custom rejection policy
        executor.setRejectedExecutionHandler(createRejectionHandler());

        // Allow core threads to timeout when idle
        executor.setAllowCoreThreadTimeOut(true);

        // Initialize the executor
        executor.initialize();

        log.info(
                "Initialized VideoProcessing ThreadPoolTaskExecutor: core={}, max={}, queue={},"
                        + " keepAlive={}s",
                corePoolSize,
                maxPoolSize,
                queueCapacity,
                keepAliveSeconds);

        return executor;
    }

    /**
     * General purpose async executor for notifications, emails, etc. Used
     * by @Async("asyncExecutor") annotations
     */
    @Bean("asyncExecutor")
    public TaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("Async-");

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();

        log.info("Initialized Async ThreadPoolTaskExecutor: core=50, max=100, queue=500");

        return executor;
    }

    /**
     * Secondary executor for lightweight async operations Handles monitoring, progress updates, and
     * cleanup tasks
     */
    @Bean("lightweightAsyncExecutor")
    public TaskExecutor lightweightAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Smaller configuration for lightweight tasks
        int lightweightCoreSize = Math.max(2, corePoolSize / 2);
        int lightweightMaxSize = Math.max(4, maxPoolSize / 2);

        executor.setCorePoolSize(lightweightCoreSize);
        executor.setMaxPoolSize(lightweightMaxSize);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(30);
        executor.setThreadNamePrefix("LightweightAsync-");

        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);

        executor.initialize();

        log.info(
                "Initialized Lightweight ThreadPoolTaskExecutor: core={}, max={}, queue=50",
                lightweightCoreSize,
                lightweightMaxSize);

        return executor;
    }

    /** Create custom rejection handler based on configuration */
    private RejectedExecutionHandler createRejectionHandler() {
        switch (rejectionPolicy.toUpperCase()) {
            case "ABORT":
                return new ThreadPoolExecutor.AbortPolicy();
            case "DISCARD":
                return new ThreadPoolExecutor.DiscardPolicy();
            case "DISCARD_OLDEST":
                return new ThreadPoolExecutor.DiscardOldestPolicy();
            case "CALLER_RUNS":
            default:
                return new VideoProcessingRejectionHandler();
        }
    }

    /** Custom rejection handler for video processing with enhanced logging */
    @Slf4j
    private static class VideoProcessingRejectionHandler implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            String taskName = r.getClass().getSimpleName();

            log.warn(
                    "Video processing task rejected: {} - Pool: active={}, core={}, max={},"
                            + " queue={}/{}",
                    taskName,
                    executor.getActiveCount(),
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getQueue().size(),
                    executor.getQueue().size() + executor.getQueue().remainingCapacity());

            // Try to execute in caller thread as fallback
            if (!executor.isShutdown()) {
                try {
                    log.info(
                            "Executing rejected video processing task in caller thread: {}",
                            taskName);
                    r.run();
                } catch (Exception e) {
                    log.error("Failed to execute rejected task in caller thread: {}", taskName, e);
                    throw new RejectedExecutionException(
                            "Task " + taskName + " rejected and failed in caller thread", e);
                }
            } else {
                throw new RejectedExecutionException(
                        "Task " + taskName + " rejected due to executor shutdown");
            }
        }
    }
}

/** Health indicator for video processing thread pool */
@Slf4j
@Component
class VideoProcessingHealthIndicator implements HealthIndicator {

    private final ThreadPoolTaskExecutor videoProcessingExecutor;
    private final ThreadPoolTaskExecutor lightweightAsyncExecutor;

    @Value("${app.async.video-processing.health.queue-threshold:0.8}")
    private double queueThreshold;

    @Value("${app.async.video-processing.health.active-threshold:0.9}")
    private double activeThreshold;

    public VideoProcessingHealthIndicator(
            @org.springframework.beans.factory.annotation.Qualifier("videoProcessingExecutor") TaskExecutor videoProcessingExecutor,
            @org.springframework.beans.factory.annotation.Qualifier("lightweightAsyncExecutor") TaskExecutor lightweightAsyncExecutor) {
        this.videoProcessingExecutor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        this.lightweightAsyncExecutor = (ThreadPoolTaskExecutor) lightweightAsyncExecutor;
    }

    @Override
    public Health health() {
        try {
            Health.Builder builder = Health.up();

            // Check video processing executor
            addExecutorHealth(builder, "videoProcessing", videoProcessingExecutor);

            // Check lightweight executor
            addExecutorHealth(builder, "lightweightAsync", lightweightAsyncExecutor);

            return builder.build();

        } catch (Exception e) {
            log.error("Error checking video processing health", e);
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }

    private void addExecutorHealth(
            Health.Builder builder, String prefix, ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();

        int activeCount = threadPool.getActiveCount();
        int corePoolSize = threadPool.getCorePoolSize();
        int maxPoolSize = threadPool.getMaximumPoolSize();
        int queueSize = threadPool.getQueue().size();
        int queueCapacity = queueSize + threadPool.getQueue().remainingCapacity();
        long completedTasks = threadPool.getCompletedTaskCount();
        long totalTasks = threadPool.getTaskCount();

        // Calculate utilization percentages
        double activeUtilization = (double) activeCount / maxPoolSize;
        double queueUtilization = (double) queueSize / queueCapacity;

        // Determine health status
        boolean isHealthy =
                activeUtilization < activeThreshold && queueUtilization < queueThreshold;

        builder.withDetail(prefix + ".status", isHealthy ? "HEALTHY" : "DEGRADED")
                .withDetail(prefix + ".activeThreads", activeCount)
                .withDetail(prefix + ".corePoolSize", corePoolSize)
                .withDetail(prefix + ".maxPoolSize", maxPoolSize)
                .withDetail(
                        prefix + ".activeUtilization",
                        String.format("%.2f%%", activeUtilization * 100))
                .withDetail(prefix + ".queueSize", queueSize)
                .withDetail(prefix + ".queueCapacity", queueCapacity)
                .withDetail(
                        prefix + ".queueUtilization",
                        String.format("%.2f%%", queueUtilization * 100))
                .withDetail(prefix + ".completedTasks", completedTasks)
                .withDetail(prefix + ".totalTasks", totalTasks)
                .withDetail(
                        prefix + ".rejectedTasks",
                        totalTasks - completedTasks - activeCount - queueSize);

        // Add warnings for high utilization
        if (activeUtilization >= activeThreshold) {
            builder.withDetail(prefix + ".warning", "High thread utilization");
        }
        if (queueUtilization >= queueThreshold) {
            builder.withDetail(prefix + ".warning", "High queue utilization");
        }
    }
}

/** Metrics and monitoring for async thread pools */
@Slf4j
@Component
class AsyncThreadPoolMonitor {

    private final ThreadPoolTaskExecutor videoProcessingExecutor;
    private final ThreadPoolTaskExecutor lightweightAsyncExecutor;

    public AsyncThreadPoolMonitor(
            @org.springframework.beans.factory.annotation.Qualifier("videoProcessingExecutor") TaskExecutor videoProcessingExecutor,
            @org.springframework.beans.factory.annotation.Qualifier("lightweightAsyncExecutor") TaskExecutor lightweightAsyncExecutor) {
        this.videoProcessingExecutor = (ThreadPoolTaskExecutor) videoProcessingExecutor;
        this.lightweightAsyncExecutor = (ThreadPoolTaskExecutor) lightweightAsyncExecutor;
    }

    /** Log thread pool statistics (called by scheduled task) */
    public void logThreadPoolStats() {
        logExecutorStats("VideoProcessing", videoProcessingExecutor);
        logExecutorStats("LightweightAsync", lightweightAsyncExecutor);
    }

    private void logExecutorStats(String name, ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();

        log.info(
                "{} ThreadPool Stats: active={}, pool={}/{}, queue={}/{}, completed={}, total={}",
                name,
                threadPool.getActiveCount(),
                threadPool.getPoolSize(),
                threadPool.getMaximumPoolSize(),
                threadPool.getQueue().size(),
                threadPool.getQueue().size() + threadPool.getQueue().remainingCapacity(),
                threadPool.getCompletedTaskCount(),
                threadPool.getTaskCount());
    }

    /** Get detailed thread pool metrics for monitoring */
    public ThreadPoolMetrics getVideoProcessingMetrics() {
        return createMetrics("videoProcessing", videoProcessingExecutor);
    }

    public ThreadPoolMetrics getLightweightAsyncMetrics() {
        return createMetrics("lightweightAsync", lightweightAsyncExecutor);
    }

    private ThreadPoolMetrics createMetrics(String name, ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();

        return ThreadPoolMetrics.builder()
                .name(name)
                .activeThreads(threadPool.getActiveCount())
                .corePoolSize(threadPool.getCorePoolSize())
                .maxPoolSize(threadPool.getMaximumPoolSize())
                .currentPoolSize(threadPool.getPoolSize())
                .queueSize(threadPool.getQueue().size())
                .queueCapacity(
                        threadPool.getQueue().size() + threadPool.getQueue().remainingCapacity())
                .completedTasks(threadPool.getCompletedTaskCount())
                .totalTasks(threadPool.getTaskCount())
                .rejectedTasks(
                        threadPool.getTaskCount()
                                - threadPool.getCompletedTaskCount()
                                - threadPool.getActiveCount()
                                - threadPool.getQueue().size())
                .activeUtilization(
                        (double) threadPool.getActiveCount() / threadPool.getMaximumPoolSize())
                .queueUtilization(
                        (double) threadPool.getQueue().size()
                                / (threadPool.getQueue().size()
                                        + threadPool.getQueue().remainingCapacity()))
                .isShutdown(threadPool.isShutdown())
                .isTerminating(threadPool.isTerminating())
                .build();
    }

    /** Check if thread pools are overloaded */
    public boolean isVideoProcessingOverloaded() {
        ThreadPoolMetrics metrics = getVideoProcessingMetrics();
        return metrics.getActiveUtilization() > 0.9 || metrics.getQueueUtilization() > 0.8;
    }

    public boolean isLightweightAsyncOverloaded() {
        ThreadPoolMetrics metrics = getLightweightAsyncMetrics();
        return metrics.getActiveUtilization() > 0.9 || metrics.getQueueUtilization() > 0.8;
    }
}

/** Metrics data structure for thread pool monitoring */
@lombok.Data
@lombok.Builder
class ThreadPoolMetrics {
    private String name;
    private int activeThreads;
    private int corePoolSize;
    private int maxPoolSize;
    private int currentPoolSize;
    private int queueSize;
    private int queueCapacity;
    private long completedTasks;
    private long totalTasks;
    private long rejectedTasks;
    private double activeUtilization;
    private double queueUtilization;
    private boolean isShutdown;
    private boolean isTerminating;
}
