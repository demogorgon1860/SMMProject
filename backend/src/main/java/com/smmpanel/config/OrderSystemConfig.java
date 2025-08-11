package com.smmpanel.config;

import com.smmpanel.config.fraud.FraudDetectionProperties;
import com.smmpanel.config.monitoring.SlaMonitoringProperties;
import com.smmpanel.config.order.OrderProcessingProperties;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({
    OrderProcessingProperties.class,
    SlaMonitoringProperties.class,
    FraudDetectionProperties.class,
    AppProperties.class,
    KafkaProperties.class,
    RedisProperties.class,
    DatabaseProperties.class,
    JpaProperties.class,
    ServerProperties.class,
    ManagementProperties.class,
    LoggingProperties.class,
    TaskProperties.class,
    Resilience4jProperties.class
})
public class OrderSystemConfig {

    /** Task executor for order processing */
    @Bean(name = "orderProcessingTaskExecutor")
    public Executor orderProcessingTaskExecutor(OrderProcessingProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getThreadPoolSize() / 2);
        executor.setMaxPoolSize(properties.getThreadPoolSize());
        executor.setQueueCapacity(properties.getBatchSize() * 10);
        executor.setThreadNamePrefix("order-processor-");
        executor.initialize();
        return executor;
    }

    /** Task scheduler for SLA monitoring */
    @Bean(name = "slaMonitoringTaskScheduler")
    public ThreadPoolTaskScheduler slaMonitoringTaskScheduler(SlaMonitoringProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getThreadPoolSize());
        scheduler.setThreadNamePrefix("sla-monitor-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
