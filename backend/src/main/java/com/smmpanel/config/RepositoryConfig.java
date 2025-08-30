package com.smmpanel.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
@EntityScan(basePackages = "com.smmpanel.entity")
@EnableJpaRepositories(
        basePackages = "com.smmpanel.repository.jpa",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager")
@EnableRedisRepositories(basePackages = "com.smmpanel.repository.redis")
public class RepositoryConfig {
    // This configuration explicitly separates JPA and Redis repositories
    // to avoid Spring Data module conflicts
}
