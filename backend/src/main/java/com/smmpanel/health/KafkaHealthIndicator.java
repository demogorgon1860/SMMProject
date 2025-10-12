package com.smmpanel.health;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.common.Node;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Enhanced Kafka Health Indicator for monitoring Kafka connectivity and cluster status */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AdminClient adminClient;

    private static final List<String> REQUIRED_TOPICS =
            List.of(
                    "smm.order.processing",
                    "smm.video.processing",
                    // "smm.youtube.processing", // Removed - no producer/consumer exists
                    "smm.payment.confirmations",
                    "smm.payment.refunds",
                    // "smm.binom.campaign.creation", // Removed - no producer/consumer exists
                    "smm.offer.assignments",
                    "smm.order.state.updates",
                    "smm.notifications", // Note: For external consumers
                    "smm.monitoring.alerts"); // Note: For external monitoring systems

    @Override
    public Health health() {
        try {
            log.debug("Checking Kafka health...");

            // Check cluster connectivity and get broker info
            DescribeClusterResult clusterResult = adminClient.describeCluster();
            Collection<Node> nodes = clusterResult.nodes().get(10, TimeUnit.SECONDS);
            String clusterId = clusterResult.clusterId().get(10, TimeUnit.SECONDS);

            if (nodes.isEmpty()) {
                return Health.down().withDetail("message", "No Kafka brokers available").build();
            }

            // Get all topics
            ListTopicsResult topicsResult = adminClient.listTopics();
            Collection<TopicListing> topics = topicsResult.listings().get(10, TimeUnit.SECONDS);
            Set<String> topicNames =
                    topics.stream().map(TopicListing::name).collect(Collectors.toSet());

            // Check for required SMM topics
            List<String> missingTopics =
                    REQUIRED_TOPICS.stream()
                            .filter(topic -> !topicNames.contains(topic))
                            .collect(Collectors.toList());

            // Count SMM topics
            long smmTopicsCount =
                    topicNames.stream().filter(name -> name.startsWith("smm.")).count();

            // Get consumer group info
            Set<String> consumerGroups =
                    adminClient.listConsumerGroups().all().get(10, TimeUnit.SECONDS).stream()
                            .map(listing -> listing.groupId())
                            .collect(Collectors.toSet());

            boolean hasSmmConsumerGroup = consumerGroups.contains("smm-panel-group");

            // Test producer connectivity
            boolean producerReady = kafkaTemplate.getProducerFactory() != null;

            log.info(
                    "Kafka health check - Cluster: {}, Brokers: {}, Topics: {}, SMM Topics: {},"
                            + " Consumer Groups: {}",
                    clusterId,
                    nodes.size(),
                    topics.size(),
                    smmTopicsCount,
                    consumerGroups.size());

            // Build health status
            Health.Builder healthBuilder;

            if (!missingTopics.isEmpty()) {
                healthBuilder =
                        Health.down()
                                .withDetail("message", "Missing required topics")
                                .withDetail("missing_topics", missingTopics);
            } else if (!hasSmmConsumerGroup) {
                // Use UP with warning detail instead of degraded
                healthBuilder =
                        Health.up()
                                .withDetail(
                                        "message",
                                        "Kafka is operational but SMM consumer group not found")
                                .withDetail("warning", "SMM consumer group missing");
            } else {
                healthBuilder =
                        Health.up()
                                .withDetail(
                                        "message",
                                        "Kafka is healthy and all required topics exist");
            }

            return healthBuilder
                    .withDetail("cluster_id", clusterId)
                    .withDetail("broker_count", nodes.size())
                    .withDetail(
                            "brokers",
                            nodes.stream()
                                    .map(node -> node.host() + ":" + node.port())
                                    .collect(Collectors.toList()))
                    .withDetail("total_topics", topics.size())
                    .withDetail("smm_topics_count", smmTopicsCount)
                    .withDetail("required_topics_present", missingTopics.isEmpty())
                    .withDetail("consumer_groups", consumerGroups.size())
                    .withDetail("smm_consumer_group_present", hasSmmConsumerGroup)
                    .withDetail("producer_ready", producerReady)
                    .build();

        } catch (Exception e) {
            log.error("Kafka health check failed: {}", e.getMessage(), e);
            return Health.down()
                    .withDetail("message", "Kafka connection failed")
                    .withDetail("error", e.getMessage())
                    .withDetail("error_type", e.getClass().getSimpleName())
                    .build();
        }
    }
}
