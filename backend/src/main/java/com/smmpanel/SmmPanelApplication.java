package com.smmpanel;

import com.smmpanel.config.order.OrderProcessingProperties;
import com.smmpanel.config.monitoring.SlaMonitoringProperties;
import com.smmpanel.config.JwtConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableJpaRepositories
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableKafka
@EnableConfigurationProperties({
    OrderProcessingProperties.class,
    SlaMonitoringProperties.class,
    JwtConfig.class
})
public class SmmPanelApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SmmPanelApplication.class, args);
    }
    
    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("SmmPanel-");
        executor.initialize();
        return executor;
    }
}