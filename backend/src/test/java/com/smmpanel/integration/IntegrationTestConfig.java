package com.smmpanel.integration;

import com.smmpanel.config.TestConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration Test Configuration
 * Imports the base test configuration and adds integration-specific settings
 */
@TestConfiguration
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "spring.redis.host=localhost",
    "spring.redis.port=6379",
    "app.exchange-rate.api-timeout-ms=1000",
    "app.currency.cache-duration=60"
})
@Import(TestConfig.class)
public class IntegrationTestConfig {
    // Integration-specific beans can be added here if needed
} 