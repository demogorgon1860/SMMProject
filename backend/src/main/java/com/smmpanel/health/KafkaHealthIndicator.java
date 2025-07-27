package com.smmpanel.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicListing;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Kafka Health Indicator for monitoring Kafka connectivity
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AdminClient adminClient;

    @Override
    public Health health() {
        try {
            // Check if we can connect to Kafka
            ListTopicsResult topicsResult = adminClient.listTopics();
            Collection<TopicListing> topics = topicsResult.listings().get(10, TimeUnit.SECONDS);
            
            // Check if required topics exist
            long requiredTopicsCount = topics.stream()
                .map(TopicListing::name)
                .filter(name -> name.startsWith("smm."))
                .count();
            
            if (requiredTopicsCount > 0) {
                return Health.up()
                    .withDetail("message", "Kafka is healthy")
                    .withDetail("topics", topics.size())
                    .withDetail("smm.topics", requiredTopicsCount)
                    .build();
            } else {
                return Health.down()
                    .withDetail("message", "No SMM topics found")
                    .withDetail("total.topics", topics.size())
                    .build();
            }
            
        } catch (Exception e) {
            log.error("Kafka health check failed: {}", e.getMessage(), e);
            return Health.down()
                .withDetail("message", "Kafka connection failed")
                .withDetail("error", e.getMessage())
                .build();
        }
    }
} 