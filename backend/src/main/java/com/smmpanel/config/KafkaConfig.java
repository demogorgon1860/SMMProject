package com.smmpanel.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic orderProcessingTopic() {
        return TopicBuilder.name("order-processing")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic videoProcessingTopic() {
        return TopicBuilder.name("video-processing")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic binomCampaignCreationTopic() {
        return TopicBuilder.name("binom-campaign-creation")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic videoProcessingRetryTopic() {
        return TopicBuilder.name("video-processing-retry")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic orderRefundTopic() {
        return TopicBuilder.name("order-refund")
                .partitions(1)
                .replicas(1)
                .build();
    }
}