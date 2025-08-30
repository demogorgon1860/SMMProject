package com.smmpanel.config;

import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated thread pool configuration for Kafka listeners to prevent thread exhaustion. This
 * configuration provides separate thread pools for different types of Kafka processing.
 */
@Slf4j
@Configuration
public class KafkaThreadPoolConfig {

    /**
     * Primary thread pool for standard Kafka listeners. Sized to handle all consumer containers
     * with their configured concurrency.
     */
    @Bean(name = "kafkaListenerTaskExecutor")
    @Primary
    public AsyncTaskExecutor kafkaListenerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core threads = (number of topics * concurrency per topic) + buffer
        // We have approximately 20 topics with concurrency of 1-3 each
        executor.setCorePoolSize(100); // Sufficient for all consumers
        executor.setMaxPoolSize(200); // Can scale up during peak load
        executor.setQueueCapacity(1000); // Large queue for message buffering
        executor.setThreadNamePrefix("kafka-listener-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setAllowCoreThreadTimeOut(false); // Keep core threads alive
        executor.setKeepAliveSeconds(60);

        // Use CallerRunsPolicy to prevent message loss
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        log.info(
                "Initialized Kafka listener thread pool with core size: {}, max size: {}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize());

        return executor;
    }

    /**
     * Dedicated thread pool for DLQ (Dead Letter Queue) processing. Separate pool ensures DLQ
     * processing doesn't interfere with main processing.
     */
    @Bean(name = "dlqKafkaListenerTaskExecutor")
    public AsyncTaskExecutor dlqKafkaListenerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // DLQ topics typically have lower throughput
        executor.setCorePoolSize(20); // Sufficient for DLQ processing
        executor.setMaxPoolSize(50); // Can scale if needed
        executor.setQueueCapacity(500); // Queue for DLQ messages
        executor.setThreadNamePrefix("dlq-kafka-listener-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setAllowCoreThreadTimeOut(true); // Allow DLQ threads to timeout
        executor.setKeepAliveSeconds(120); // Longer keep-alive for DLQ

        // Use CallerRunsPolicy for DLQ as well
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();

        log.info(
                "Initialized DLQ Kafka listener thread pool with core size: {}, max size: {}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize());

        return executor;
    }

    /** High-priority thread pool for critical processing (payments, orders). */
    @Bean(name = "highPriorityKafkaListenerTaskExecutor")
    public AsyncTaskExecutor highPriorityKafkaListenerTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(30);
        executor.setMaxPoolSize(60);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("high-priority-kafka-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setKeepAliveSeconds(30);
        executor.setThreadPriority(Thread.MAX_PRIORITY);

        // Abort policy for high priority - fail fast
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        executor.initialize();

        log.info(
                "Initialized high-priority Kafka listener thread pool with core size: {}, max size:"
                        + " {}",
                executor.getCorePoolSize(),
                executor.getMaxPoolSize());

        return executor;
    }
}
