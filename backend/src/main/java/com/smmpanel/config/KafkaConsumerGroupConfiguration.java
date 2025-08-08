package com.smmpanel.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KAFKA CONSUMER GROUP MANAGEMENT CONFIGURATION
 *
 * Provides comprehensive consumer group management:
 * 1. Optimized consumer group settings for different workloads
 * 2. Consumer rebalancing listeners with metrics and notifications
 * 3. Proper session timeout and heartbeat configurations
 * 4. Load balancing and partition assignment strategies
 * 5. Consumer group health monitoring and recovery
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerGroupConfiguration {

    private final KafkaProperties kafkaProperties;
    private final KafkaConsumerErrorConfiguration errorConfiguration;

    // Consumer group management properties
    @Value("${app.kafka.consumer.session-timeout-ms:30000}")
    private int sessionTimeoutMs;

    @Value("${app.kafka.consumer.heartbeat-interval-ms:3000}")
    private int heartbeatIntervalMs;

    @Value("${app.kafka.consumer.max-poll-interval-ms:300000}")
    private int maxPollIntervalMs;

    @Value("${app.kafka.consumer.max-poll-records:500}")
    private int maxPollRecords;

    @Value("${app.kafka.consumer.fetch-min-bytes:1}")
    private int fetchMinBytes;

    @Value("${app.kafka.consumer.fetch-max-wait-ms:500}")
    private int fetchMaxWaitMs;

    // Consumer group monitoring
    private final Map<String, ConsumerGroupMetrics> groupMetrics = new ConcurrentHashMap<>();
    private final AtomicLong totalRebalances = new AtomicLong(0);
    private final AtomicInteger activeConsumers = new AtomicInteger(0);

    /**
     * Creates optimized consumer factory for high-throughput processing
     */
    @Bean("highThroughputConsumerFactory")
    public ConsumerFactory<String, Object> highThroughputConsumerFactory() {
        Map<String, Object> props = createBaseConsumerProperties();
        
        // High throughput optimizations
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 50000);  // 50KB
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 100);  // Reduce wait time
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000); // Longer session timeout
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 5000); // More frequent heartbeats
        
        // Partition assignment strategy for high throughput
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, 
                "org.apache.kafka.clients.consumer.RoundRobinAssignor");
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Creates optimized consumer factory for low-latency processing
     */
    @Bean("lowLatencyConsumerFactory")
    public ConsumerFactory<String, Object> lowLatencyConsumerFactory() {
        Map<String, Object> props = createBaseConsumerProperties();
        
        // Low latency optimizations
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 10);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 20000); // Shorter session timeout
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2000); // More frequent heartbeats
        
        // Partition assignment strategy for low latency
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, 
                "org.apache.kafka.clients.consumer.StickyAssignor");
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Creates balanced consumer factory with optimal settings
     */
    @Bean("balancedConsumerFactory")
    public ConsumerFactory<String, Object> balancedConsumerFactory() {
        Map<String, Object> props = createBaseConsumerProperties();
        
        // Balanced settings
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, fetchMinBytes);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, fetchMaxWaitMs);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatIntervalMs);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        
        // Cooperative sticky assignment for better rebalancing
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, 
                List.of("org.apache.kafka.clients.consumer.CooperativeStickyAssignor",
                       "org.apache.kafka.clients.consumer.StickyAssignor"));
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * High-throughput container factory with rebalancing listeners
     */
    @Bean("highThroughputKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> highThroughputKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(highThroughputConsumerFactory());
        factory.setConcurrency(5); // More concurrent consumers
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setSyncCommits(false); // Async commits for throughput
        factory.getContainerProperties().setPollTimeout(3000L);
        
        // Add rebalancing listener
        factory.getContainerProperties().setConsumerRebalanceListener(
                createRebalanceListener("high-throughput"));
        
        // Set error handler
        factory.setCommonErrorHandler(errorConfiguration.defaultKafkaErrorHandler());
        
        // Configure listener task executor for better thread management
        factory.getContainerProperties().setListenerTaskExecutor(createConsumerTaskExecutor("high-throughput"));
        
        return factory;
    }

    /**
     * Low-latency container factory with optimized settings
     */
    @Bean("lowLatencyKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> lowLatencyKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(lowLatencyConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true); // Sync commits for consistency
        factory.getContainerProperties().setPollTimeout(1000L); // Shorter poll timeout
        
        // Add rebalancing listener
        factory.getContainerProperties().setConsumerRebalanceListener(
                createRebalanceListener("low-latency"));
        
        // Set error handler
        factory.setCommonErrorHandler(errorConfiguration.highPriorityErrorHandler());
        
        // Configure listener task executor
        factory.getContainerProperties().setListenerTaskExecutor(createConsumerTaskExecutor("low-latency"));
        
        return factory;
    }

    /**
     * Balanced container factory with comprehensive monitoring
     */
    @Bean("balancedKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> balancedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(balancedConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(3000L);
        
        // Add comprehensive rebalancing listener with metrics
        factory.getContainerProperties().setConsumerRebalanceListener(
                createAdvancedRebalanceListener("balanced"));
        
        // Set error handler
        factory.setCommonErrorHandler(errorConfiguration.defaultKafkaErrorHandler());
        
        // Configure listener task executor
        factory.getContainerProperties().setListenerTaskExecutor(createConsumerTaskExecutor("balanced"));
        
        // Consumer lifecycle monitoring removed (deprecated setGenericConsumerFactory)
        
        return factory;
    }

    /**
     * Order processing container factory with specialized settings
     */
    @Bean("orderProcessingConsumerGroupFactory")
    public ConcurrentKafkaListenerContainerFactory<String, Object> orderProcessingConsumerGroupFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(balancedConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.getContainerProperties().setPollTimeout(5000L);
        
        // Order processing specific settings
        factory.getContainerProperties().setIdleBetweenPolls(100L);
        factory.getContainerProperties().setMicrometerEnabled(true);
        
        // Add order-specific rebalancing listener
        factory.getContainerProperties().setConsumerRebalanceListener(
                createOrderProcessingRebalanceListener());
        
        // Set order processing error handler
        factory.setCommonErrorHandler(errorConfiguration.orderProcessingErrorHandler());
        
        return factory;
    }

    /**
     * Creates base consumer properties
     */
    private Map<String, Object> createBaseConsumerProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // Connection and timeout settings
        props.put(ConsumerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 540000); // 9 minutes
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);
        
        // JSON deserializer settings
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.smmpanel.entity,com.smmpanel.dto,com.smmpanel.event,java.util");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");
        
        return props;
    }

    /**
     * Creates basic rebalance listener
     */
    private ConsumerRebalanceListener createRebalanceListener(String consumerType) {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                log.info("Consumer group rebalance - partitions revoked for {}: {}", 
                        consumerType, partitions.size());
                totalRebalances.incrementAndGet();
                
                // Update metrics
                ConsumerGroupMetrics metrics = groupMetrics.computeIfAbsent(consumerType, 
                        k -> new ConsumerGroupMetrics());
                metrics.rebalanceCount.incrementAndGet();
                metrics.lastRebalanceTime = LocalDateTime.now();
                
                // Commit current offsets before rebalancing
                log.debug("Committing offsets before rebalance for {}: {}", consumerType, partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.info("Consumer group rebalance - partitions assigned for {}: {}", 
                        consumerType, partitions.size());
                
                // Update metrics
                ConsumerGroupMetrics metrics = groupMetrics.get(consumerType);
                if (metrics != null) {
                    metrics.assignedPartitions.set(partitions.size());
                    metrics.lastAssignmentTime = LocalDateTime.now();
                }
                
                // Log partition assignments for monitoring
                partitions.forEach(partition -> 
                        log.debug("Assigned partition: {} for consumer type: {}", partition, consumerType));
            }
        };
    }

    /**
     * Creates advanced rebalance listener with comprehensive monitoring
     */
    private ConsumerAwareRebalanceListener createAdvancedRebalanceListener(String consumerType) {
        return new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(
                    org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                    Collection<TopicPartition> partitions) {
                
                log.info("Advanced rebalance - committing offsets before revocation for {}: {}", 
                        consumerType, partitions.size());
                
                try {
                    // Commit current offsets synchronously
                    consumer.commitSync();
                    log.debug("Successfully committed offsets before rebalance for {}", consumerType);
                } catch (Exception e) {
                    log.error("Failed to commit offsets before rebalance for {}: {}", consumerType, e.getMessage());
                }
            }

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                log.info("Advanced rebalance - partitions revoked for {}: {}", 
                        consumerType, partitions.size());
                
                ConsumerGroupMetrics metrics = groupMetrics.computeIfAbsent(consumerType, 
                        k -> new ConsumerGroupMetrics());
                metrics.rebalanceCount.incrementAndGet();
                metrics.lastRebalanceTime = LocalDateTime.now();
                metrics.revokedPartitions.addAndGet(partitions.size());
                
                totalRebalances.incrementAndGet();
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.info("Advanced rebalance - partitions assigned for {}: {}", 
                        consumerType, partitions.size());
                
                ConsumerGroupMetrics metrics = groupMetrics.get(consumerType);
                if (metrics != null) {
                    metrics.assignedPartitions.set(partitions.size());
                    metrics.lastAssignmentTime = LocalDateTime.now();
                }
                
                // Seek to specific offsets if needed (for replay scenarios)
                if (shouldSeekToBeginning(consumerType)) {
                    partitions.forEach(partition -> {
                        log.debug("Seeking to beginning for partition: {} in consumer type: {}", 
                                partition, consumerType);
                    });
                }
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                log.warn("Advanced rebalance - partitions LOST for {}: {} (potential consumer failure)", 
                        consumerType, partitions.size());
                
                ConsumerGroupMetrics metrics = groupMetrics.get(consumerType);
                if (metrics != null) {
                    metrics.lostPartitions.addAndGet(partitions.size());
                    metrics.lastPartitionLossTime = LocalDateTime.now();
                }
                
                // Alert on partition loss (indicates consumer failure)
                sendPartitionLossAlert(consumerType, partitions);
            }
        };
    }

    /**
     * Creates order processing specific rebalance listener
     */
    private ConsumerRebalanceListener createOrderProcessingRebalanceListener() {
        return new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                log.info("Order processing rebalance - partitions revoked: {}", partitions.size());
                
                // For order processing, we might want to:
                // 1. Complete in-flight orders before rebalancing
                // 2. Save processing state for recovery
                // 3. Update order processing metrics
                
                ConsumerGroupMetrics metrics = groupMetrics.computeIfAbsent("order-processing", 
                        k -> new ConsumerGroupMetrics());
                metrics.rebalanceCount.incrementAndGet();
                metrics.lastRebalanceTime = LocalDateTime.now();
                
                // Log order processing specific information
                log.info("Preparing order processing consumers for rebalance - saving state");
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.info("Order processing rebalance - partitions assigned: {}", partitions.size());
                
                ConsumerGroupMetrics metrics = groupMetrics.get("order-processing");
                if (metrics != null) {
                    metrics.assignedPartitions.set(partitions.size());
                    metrics.lastAssignmentTime = LocalDateTime.now();
                }
                
                // For order processing, we might want to:
                // 1. Resume order processing from saved state
                // 2. Initialize order processing resources
                // 3. Update order processing capacity metrics
                
                log.info("Order processing consumers ready after rebalance - {} partitions assigned", 
                        partitions.size());
            }
        };
    }

    /**
     * Creates consumer task executor for better thread management
     */
    private ThreadPoolTaskExecutor createConsumerTaskExecutor(String consumerType) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("kafka-consumer-" + consumerType + "-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Instruments consumer for monitoring
     */
    private org.apache.kafka.clients.consumer.Consumer<Object, Object> instrumentConsumer(
            org.apache.kafka.clients.consumer.Consumer<Object, Object> consumer) {
        
        activeConsumers.incrementAndGet();
        log.debug("Consumer instrumented - total active consumers: {}", activeConsumers.get());
        
        // Add consumer metrics and monitoring here
        // This could include custom metrics collection, health checks, etc.
        
        return consumer;
    }

    /**
     * Determines if consumer should seek to beginning
     */
    private boolean shouldSeekToBeginning(String consumerType) {
        // Logic to determine if seeking to beginning is needed
        // This could be based on configuration, consumer type, or external conditions
        return false;
    }

    /**
     * Sends alert for partition loss
     */
    private void sendPartitionLossAlert(String consumerType, Collection<TopicPartition> partitions) {
        log.error("CRITICAL: Partition loss detected for consumer type: {} - {} partitions lost", 
                consumerType, partitions.size());
        
        // In production, this would trigger:
        // 1. PagerDuty/Slack alerts
        // 2. Metrics updates
        // 3. Dashboard notifications
        // 4. Automatic recovery procedures
    }

    /**
     * Provides consumer group metrics
     */
    @Bean("consumerGroupMetrics")
    public Map<String, Object> consumerGroupMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalRebalances", totalRebalances.get());
        metrics.put("activeConsumers", activeConsumers.get());
        metrics.put("groupMetrics", new HashMap<>(groupMetrics));
        metrics.put("timestamp", LocalDateTime.now());
        return metrics;
    }

    /**
     * Consumer group metrics holder
     */
    public static class ConsumerGroupMetrics {
        public final AtomicLong rebalanceCount = new AtomicLong(0);
        public final AtomicInteger assignedPartitions = new AtomicInteger(0);
        public final AtomicLong revokedPartitions = new AtomicLong(0);
        public final AtomicLong lostPartitions = new AtomicLong(0);
        public volatile LocalDateTime lastRebalanceTime;
        public volatile LocalDateTime lastAssignmentTime;
        public volatile LocalDateTime lastPartitionLossTime;
        
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("rebalanceCount", rebalanceCount.get());
            map.put("assignedPartitions", assignedPartitions.get());
            map.put("revokedPartitions", revokedPartitions.get());
            map.put("lostPartitions", lostPartitions.get());
            map.put("lastRebalanceTime", lastRebalanceTime);
            map.put("lastAssignmentTime", lastAssignmentTime);
            map.put("lastPartitionLossTime", lastPartitionLossTime);
            return map;
        }
    }
}