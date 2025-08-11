package com.smmpanel.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * ERROR RECOVERY CONFIGURATION
 *
 * <p>Configures async processing for error recovery operations: 1. Thread pool for retry processing
 * 2. Dead letter queue processing configuration 3. Scheduling configuration for cleanup tasks 4.
 * Error recovery monitoring setup
 */
@Configuration
@EnableAsync
@EnableScheduling
public class ErrorRecoveryConfig {

    @Value("${app.error-recovery.thread-pool.core-size:2}")
    private int errorRecoveryCorePoolSize;

    @Value("${app.error-recovery.thread-pool.max-size:4}")
    private int errorRecoveryMaxPoolSize;

    @Value("${app.error-recovery.thread-pool.queue-capacity:50}")
    private int errorRecoveryQueueCapacity;

    @Value("${app.error-recovery.thread-pool.keep-alive-seconds:60}")
    private int errorRecoveryKeepAliveSeconds;

    /** ERROR RECOVERY EXECUTOR Dedicated thread pool for processing retry operations */
    @Bean("errorRecoveryExecutor")
    public Executor errorRecoveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(errorRecoveryCorePoolSize);
        executor.setMaxPoolSize(errorRecoveryMaxPoolSize);
        executor.setQueueCapacity(errorRecoveryQueueCapacity);
        executor.setKeepAliveSeconds(errorRecoveryKeepAliveSeconds);
        executor.setThreadNamePrefix("ErrorRecovery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Handle rejected tasks by running them in the caller thread
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }

    // DEAD LETTER QUEUE KAFKA LISTENER CONTAINER FACTORY IS CONFIGURED IN KafkaConfig.java
}

/** ERROR RECOVERY PROPERTIES Configuration properties for error recovery settings */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "app.error-recovery")
@org.springframework.stereotype.Component
@lombok.Data
class ErrorRecoveryProperties {

    private int maxRetries = 3;
    private int initialDelayMinutes = 5;
    private int maxDelayHours = 24;
    private double backoffMultiplier = 2.0;

    private ThreadPool threadPool = new ThreadPool();
    private DeadLetterQueue deadLetterQueue = new DeadLetterQueue();
    private Cleanup cleanup = new Cleanup();

    @lombok.Data
    public static class ThreadPool {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 50;
        private int keepAliveSeconds = 60;
    }

    @lombok.Data
    public static class DeadLetterQueue {
        private String topic = "video.processing.dlq";
        private int retentionDays = 30;
        private int maxEntries = 10000;
        private String cleanupCron = "0 2 * * * *"; // Daily at 2 AM
    }

    @lombok.Data
    public static class Cleanup {
        private String cron = "0 */30 * * * *"; // Every 30 minutes
        private int retryProcessingIntervalMinutes = 5;
    }
}
