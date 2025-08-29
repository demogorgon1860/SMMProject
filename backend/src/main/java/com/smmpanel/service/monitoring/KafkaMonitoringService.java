package com.smmpanel.service.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Kafka Monitoring Service Provides comprehensive monitoring for Kafka consumers including: -
 * Consumer lag monitoring - Processing rate tracking - Error rate monitoring - Consumer group
 * health checks
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaMonitoringService {

    private final MeterRegistry meterRegistry;
    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Value("${kafka.monitoring.lag.threshold:10000}")
    private long lagThreshold;

    @Value("${kafka.monitoring.error.threshold:0.05}")
    private double errorRateThreshold;

    private final Map<String, Timer> processingTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> consumerLags = new ConcurrentHashMap<>();

    /** Initialize monitoring for a consumer group */
    public void initializeConsumerGroupMonitoring(String groupId, String topic) {
        // Processing time metrics
        Timer timer =
                Timer.builder("kafka.consumer.processing")
                        .tag("group", groupId)
                        .tag("topic", topic)
                        .description("Message processing time")
                        .register(meterRegistry);
        processingTimers.put(groupId, timer);

        // Error counter
        Counter errorCounter =
                Counter.builder("kafka.consumer.errors")
                        .tag("group", groupId)
                        .tag("topic", topic)
                        .description("Message processing errors")
                        .register(meterRegistry);
        errorCounters.put(groupId, errorCounter);

        // Consumer lag gauge
        AtomicLong lagValue = new AtomicLong(0);
        consumerLags.put(groupId, lagValue);
        Gauge.builder("kafka.consumer.lag", lagValue, AtomicLong::get)
                .tag("group", groupId)
                .tag("topic", topic)
                .description("Consumer lag in messages")
                .register(meterRegistry);

        log.info("Initialized monitoring for consumer group: {}, topic: {}", groupId, topic);
    }

    /** Record message processing time */
    public void recordProcessingTime(String groupId, long milliseconds) {
        Timer timer = processingTimers.get(groupId);
        if (timer != null) {
            timer.record(milliseconds, TimeUnit.MILLISECONDS);
        }
    }

    /** Record processing error */
    public void recordError(String groupId) {
        Counter counter = errorCounters.get(groupId);
        if (counter != null) {
            counter.increment();
        }
    }

    /** Update consumer lag metrics */
    @Scheduled(fixedRate = 60000) // Every minute
    public void updateConsumerLagMetrics() {
        kafkaListenerEndpointRegistry
                .getAllListenerContainers()
                .forEach(
                        container -> {
                            String groupId = container.getGroupId();
                            if (groupId != null) {
                                long totalLag = calculateConsumerLag(container);

                                // Initialize monitoring if not already done
                                AtomicLong lagCounter = consumerLags.get(groupId);
                                if (lagCounter == null) {
                                    log.debug(
                                            "Initializing monitoring for consumer group: {}",
                                            groupId);
                                    // Initialize monitoring with default topic name
                                    initializeConsumerGroupMonitoring(groupId, "unknown");
                                    lagCounter = consumerLags.get(groupId);
                                }

                                if (lagCounter != null) {
                                    lagCounter.set(totalLag);
                                }

                                if (totalLag > lagThreshold) {
                                    log.warn(
                                            "High consumer lag detected for group {}: {} messages",
                                            groupId,
                                            totalLag);
                                }
                            }
                        });
    }

    /** Check consumer group health */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void checkConsumerGroupHealth() {
        try {
            kafkaListenerEndpointRegistry
                    .getAllListenerContainers()
                    .forEach(
                            container -> {
                                String groupId = container.getGroupId();
                                if (groupId != null) {
                                    // Initialize monitoring if not already done
                                    if (!consumerLags.containsKey(groupId)) {
                                        initializeConsumerGroupMonitoring(groupId, "unknown");
                                    }

                                    boolean isHealthy = isConsumerGroupHealthy(container);
                                    String status = isHealthy ? "HEALTHY" : "UNHEALTHY";

                                    // Use a unique gauge name with tags to avoid re-registration
                                    meterRegistry.gauge(
                                            "kafka.consumer.health",
                                            java.util.List.of(
                                                    io.micrometer.core.instrument.Tag.of(
                                                            "group", groupId),
                                                    io.micrometer.core.instrument.Tag.of(
                                                            "status", status)),
                                            isHealthy ? 1 : 0);

                                    if (!isHealthy) {
                                        log.warn("Consumer group {} is unhealthy", groupId);
                                    }
                                }
                            });
        } catch (Exception e) {
            log.error("Error checking consumer group health: {}", e.getMessage(), e);
        }
    }

    /** Calculate processing rate for a consumer group */
    public double getProcessingRate(String groupId) {
        Timer timer = processingTimers.get(groupId);
        if (timer != null) {
            return timer.count() / timer.totalTime(TimeUnit.SECONDS);
        }
        return 0.0;
    }

    /** Calculate error rate for a consumer group */
    public double getErrorRate(String groupId) {
        Counter errorCounter = errorCounters.get(groupId);
        Timer timer = processingTimers.get(groupId);
        if (errorCounter != null && timer != null && timer.count() > 0) {
            double errorRate = errorCounter.count() / (double) timer.count();
            if (errorRate > errorRateThreshold) {
                log.warn(
                        "High error rate detected for consumer group {}: {}%",
                        groupId, String.format("%.2f", errorRate * 100));
            }
            return errorRate;
        }
        return 0.0;
    }

    private long calculateConsumerLag(MessageListenerContainer container) {
        try {
            // TODO restore - getAssignedConsumer method when available
            // For now, return 0 to prevent issues
            // Consumer<?, ?> consumer = container.getAssignedConsumer();
            // The method doesn't exist in current Spring Kafka version

            // Temporary workaround: return 0 lag as we can't access the consumer
            // This prevents NullPointerException but doesn't provide real lag metrics
            log.trace(
                    "Consumer lag calculation not available for group: {}", container.getGroupId());
            return 0L;

            /* Implementation when getAssignedConsumer is available:
            if (consumer != null) {
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(consumer.assignment());

                return endOffsets.entrySet().stream()
                        .mapToLong(
                                entry -> {
                                    long currentOffset = consumer.position(entry.getKey());
                                    return entry.getValue() - currentOffset;
                                })
                        .sum();
            }
            */
        } catch (Exception e) {
            log.error(
                    "Error calculating consumer lag for group {}: {}",
                    container.getGroupId(),
                    e.getMessage());
        }
        return 0L;
    }

    private boolean isConsumerGroupHealthy(MessageListenerContainer container) {
        try {
            if (!container.isRunning()) {
                log.debug("Container not running for group: {}", container.getGroupId());
                return false;
            }

            // Check for excessive lag (currently returns 0 due to API limitation)
            long lag = calculateConsumerLag(container);
            if (lag > lagThreshold) {
                log.debug("High lag detected for group {}: {}", container.getGroupId(), lag);
                return false;
            }

            String groupId = container.getGroupId();
            if (groupId == null) {
                return false;
            }

            // Check error rate
            double errorRate = getErrorRate(groupId);
            if (errorRate > errorRateThreshold) {
                log.debug("High error rate for group {}: {}", groupId, errorRate);
                return false;
            }

            // Check processing rate - only if we have processed messages
            Timer timer = processingTimers.get(groupId);
            if (timer != null && timer.count() > 0) {
                double rate = getProcessingRate(groupId);
                if (rate < 0.1) { // Less than 0.1 messages per second
                    log.debug("Low processing rate for group {}: {}", groupId, rate);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error(
                    "Error checking health for container group {}: {}",
                    container.getGroupId(),
                    e.getMessage());
            return false;
        }
    }
}
