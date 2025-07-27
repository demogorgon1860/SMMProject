package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Kafka Configuration Properties
 */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.kafka")
public class KafkaProperties {

    @NotBlank
    private String bootstrapServers = "localhost:9092";

    private Producer producer = new Producer();
    private Consumer consumer = new Consumer();

    @Data
    public static class Producer {
        @NotBlank
        private String keySerializer = "org.apache.kafka.common.serialization.StringSerializer";
        
        @NotBlank
        private String valueSerializer = "org.springframework.kafka.support.serializer.JsonSerializer";
        
        @NotBlank
        private String acks = "all";
        
        @Min(0)
        private int retries = 3;
        
        @Min(1)
        private int batchSize = 16384;
        
        @Min(0)
        private int lingerMs = 1;
        
        @Min(1)
        private int bufferMemory = 33554432;
        
        private Properties properties = new Properties();
        
        @Data
        public static class Properties {
            private boolean enableIdempotence = true;
            private String compressionType = "snappy";
            private int deliveryTimeoutMs = 120000;
            private int requestTimeoutMs = 30000;
            private int maxInFlightRequestsPerConnection = 5;
            private String springJsonTypeMapping = 
                "order:com.smmpanel.entity.Order," +
                "videoProcessing:com.smmpanel.entity.VideoProcessing," +
                "offerAssignment:com.smmpanel.dto.binom.OfferAssignmentRequest," +
                "offerAssignmentEvent:com.smmpanel.event.OfferAssignmentEvent," +
                "notification:java.util.Map," +
                "orderStateUpdate:java.util.Map";
        }
    }

    @Data
    public static class Consumer {
        @NotBlank
        private String keyDeserializer = "org.apache.kafka.common.serialization.StringDeserializer";
        
        @NotBlank
        private String valueDeserializer = "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer";
        
        @NotBlank
        private String groupId = "smm-panel-group";
        
        @NotBlank
        private String autoOffsetReset = "earliest";
        
        private boolean enableAutoCommit = false;
        
        @Min(1)
        private int maxPollRecords = 500;
        
        @Min(1)
        private int fetchMinBytes = 1;
        
        @Min(1)
        private int fetchMaxWaitMs = 500;
        
        @Min(1)
        private int sessionTimeoutMs = 30000;
        
        @Min(1)
        private int heartbeatIntervalMs = 3000;
        
        @Min(1)
        private int maxPollIntervalMs = 300000;
        
        private Properties properties = new Properties();
        
        @Data
        public static class Properties {
            private String springDeserializerValueDelegateClass = "org.springframework.kafka.support.serializer.JsonDeserializer";
            private String springJsonTrustedPackages = "com.smmpanel.entity,com.smmpanel.dto,com.smmpanel.event,java.util";
            private String springJsonTypeMapping = 
                "order:com.smmpanel.entity.Order," +
                "videoProcessing:com.smmpanel.entity.VideoProcessing," +
                "offerAssignment:com.smmpanel.dto.binom.OfferAssignmentRequest," +
                "offerAssignmentEvent:com.smmpanel.event.OfferAssignmentEvent," +
                "notification:java.util.Map," +
                "orderStateUpdate:java.util.Map";
            private boolean springJsonUseTypeHeaders = false;
            private String springJsonValueDefaultType = "java.util.Map";
        }
    }
} 