package com.smmpanel.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import com.smmpanel.service.monitoring.MemoryMonitoringService;

import java.util.HashMap;
import java.util.Map;

/**
 * Memory-Optimized Kafka Consumer Configuration
 * Configures Kafka consumers with optimal memory settings and monitoring
 */
@Configuration
public class KafkaMemoryOptimizedConsumerConfig {

    @Autowired
    private KafkaConsumerMemoryConfig memoryConfig;
    
    @Autowired
    private MemoryMonitoringService memoryMonitoringService;

    @Bean
    public ConsumerFactory<String, Object> memoryOptimizedConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        
        // Memory-related configurations
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, memoryConfig.getFetchMaxBytes());
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, memoryConfig.getMaxPartitionFetchBytes());
        props.put(ConsumerConfig.RECEIVE_BUFFER_CONFIG, memoryConfig.getReceiveBufferBytes());
        props.put(ConsumerConfig.SEND_BUFFER_CONFIG, memoryConfig.getSendBufferBytes());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, memoryConfig.getMaxPollRecords());
        
        // Performance optimizations
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> memoryOptimizedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(memoryOptimizedConsumerFactory());
        
        // Configure container properties
        ContainerProperties props = factory.getContainerProperties();
        props.setIdleBetweenPolls(100); // Reduce CPU usage when idle
        props.setPollTimeout(3000);
        props.setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // Add memory monitoring listener
        factory.setRecordInterceptor((record, consumer) -> {
            // Monitor memory after each record
            memoryMonitoringService.updateMemoryMetrics();
            return record;
        });
        
        return factory;
    }
    
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchMemoryOptimizedKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(memoryOptimizedConsumerFactory());
        factory.setBatchListener(true);
        
        // Configure container properties for batch processing
        ContainerProperties props = factory.getContainerProperties();
        props.setIdleBetweenPolls(100);
        props.setPollTimeout(5000);
        props.setAckMode(ContainerProperties.AckMode.MANUAL);
        
        // Set batch error handler
        factory.setBatchErrorHandler((exception, data) -> {
            // Log memory usage on batch errors
            memoryMonitoringService.getMemoryUsageSummary().getFormattedSummary();
            throw exception;
        });
        
        return factory;
    }
}
