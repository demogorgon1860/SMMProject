package com.smmpanel;

import com.smmpanel.config.AppProperties;
import com.smmpanel.config.JwtConfig;
import com.smmpanel.config.monitoring.SlaMonitoringProperties;
import com.smmpanel.config.order.OrderProcessingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.smmpanel.repository.jpa")
@EnableRedisRepositories(basePackages = "com.smmpanel.repository.redis")
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableKafka
@EnableConfigurationProperties({
    AppProperties.class,
    OrderProcessingProperties.class,
    SlaMonitoringProperties.class,
    JwtConfig.class
})
public class SmmPanelApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmmPanelApplication.class, args);
    }

    // taskExecutor is configured in AsyncConfig.java
}
