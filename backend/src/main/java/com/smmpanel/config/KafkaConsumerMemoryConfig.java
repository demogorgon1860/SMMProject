package com.smmpanel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Data;

/**
 * Kafka Consumer Memory Configuration
 * Contains memory-related settings for Kafka consumers
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "kafka.consumer.memory")
public class KafkaConsumerMemoryConfig {
    
    /**
     * Maximum fetch request size in bytes (default: 50MB)
     */
    private int fetchMaxBytes = 52428800;
    
    /**
     * Maximum partition fetch size in bytes (default: 1MB)
     */
    private int maxPartitionFetchBytes = 1048576;
    
    /**
     * Send buffer size in bytes (default: 128KB)
     */
    private int sendBufferBytes = 131072;
    
    /**
     * Receive buffer size in bytes (default: 256KB)
     */
    private int receiveBufferBytes = 262144;
    
    /**
     * Maximum request size in bytes (default: 100MB)
     */
    private int maxRequestSize = 104857600;
    
    /**
     * Buffer size for consumer records (default: 64MB)
     */
    private int bufferSize = 67108864;
    
    /**
     * Maximum poll records per consumer (default: 500)
     */
    private int maxPollRecords = 500;
    
    /**
     * JVM heap size settings
     */
    private JvmHeapSettings heap = new JvmHeapSettings();
    
    @Data
    public static class JvmHeapSettings {
        private String initialSize = "1g";
        private String maxSize = "4g";
        private String youngGenSize = "256m";
        private boolean useG1GC = true;
        private int g1NewSizePercent = 20;
        private int g1MaxNewSizePercent = 60;
        private int initiatingHeapOccupancyPercent = 45;
    }
    
    /**
     * Get JVM arguments for Kafka consumer processes
     */
    public String[] getJvmArgs() {
        return new String[] {
            "-Xms" + heap.getInitialSize(),
            "-Xmx" + heap.getMaxSize(),
            "-Xmn" + heap.getYoungGenSize(),
            "-XX:+Use" + (heap.isUseG1GC() ? "G1GC" : "ParallelGC"),
            "-XX:G1NewSizePercent=" + heap.getG1NewSizePercent(),
            "-XX:G1MaxNewSizePercent=" + heap.getG1MaxNewSizePercent(),
            "-XX:InitiatingHeapOccupancyPercent=" + heap.getInitiatingHeapOccupancyPercent(),
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=logs/kafka-consumer-heap-dump.hprof",
            "-XX:+ExitOnOutOfMemoryError"
        };
    }
}
