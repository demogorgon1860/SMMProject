package com.smmpanel.config;

import java.util.Collection;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StableConsumerRebalanceListener implements ConsumerRebalanceListener {

    private static final Logger log =
            LoggerFactory.getLogger(StableConsumerRebalanceListener.class);
    private final String consumerGroup;

    public StableConsumerRebalanceListener(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        log.info("Consumer group '{}' - Partitions revoked: {}", consumerGroup, partitions);
        // Add graceful shutdown logic here if needed
        try {
            Thread.sleep(1000); // Small delay to allow in-flight messages to complete
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during partition revocation for group '{}'", consumerGroup);
        }
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        log.info("Consumer group '{}' - Partitions assigned: {}", consumerGroup, partitions);
        // Add initialization logic here if needed
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
        log.warn("Consumer group '{}' - Partitions lost: {}", consumerGroup, partitions);
        // Handle lost partitions - typically same as revoked
        onPartitionsRevoked(partitions);
    }
}
