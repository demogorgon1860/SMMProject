# Kafka Configuration Fixes

## Overview
This document outlines the comprehensive fixes applied to the Kafka configuration in the SMM Panel application to ensure production-ready reliability, proper error handling, and consistent topic naming.

## Issues Fixed

### 1. Topic Name Inconsistencies
**Problem**: Services were using inconsistent topic names that didn't match the configuration.

**Fixed Topics**:
- `youtube-processing` → `smm.youtube.processing`
- `video-processing` → `smm.video.processing`
- `order-refund` → `smm.order.refund`
- `notifications.*` → `smm.notifications`

**Files Updated**:
- `OrderService.java`
- `VideoProcessingService.java`
- `YouTubeAutomationService.java`
- `OrderProcessingService.java`
- `NotificationService.java`
- `KafkaProducers.java`

### 2. Proper Serializers/Deserializers Configuration

**Producer Configuration**:
```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        spring.json.type.mapping: >
          order:com.smmpanel.entity.Order,
          videoProcessing:com.smmpanel.entity.VideoProcessing,
          offerAssignment:com.smmpanel.dto.binom.OfferAssignmentRequest,
          offerAssignmentEvent:com.smmpanel.event.OfferAssignmentEvent,
          notification:java.util.Map,
          orderStateUpdate:java.util.Map
```

**Consumer Configuration**:
```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: com.smmpanel.entity,com.smmpanel.dto,com.smmpanel.event,java.util
        spring.json.type.mapping: >
          order:com.smmpanel.entity.Order,
          videoProcessing:com.smmpanel.entity.VideoProcessing,
          offerAssignment:com.smmpanel.dto.binom.OfferAssignmentRequest,
          offerAssignmentEvent:com.smmpanel.event.OfferAssignmentEvent,
          notification:java.util.Map,
          orderStateUpdate:java.util.Map
```

### 3. Error Handling and Dead Letter Queue (DLQ)

**New Error Handler Configuration**:
- Created `KafkaErrorHandlerConfig.java` for centralized error handling
- Implements retry logic with exponential backoff
- Automatically sends failed messages to DLQ topics
- Configures non-retryable exceptions

**DLQ Topics Created**:
- `smm.order.processing.dlq`
- `smm.video.processing.dlq`
- `smm.youtube.processing.dlq`
- `smm.offer.assignments.dlq`
- `smm.order.refund.dlq`
- `smm.binom.campaign.creation.dlq`
- `smm.video.processing.retry.dlq`
- `smm.order.state.updates.dlq`
- `smm.notifications.dlq`

**Error Handler Features**:
- 3 retry attempts with 1-second delay
- Comprehensive error logging
- DLQ message includes original message + error metadata
- Non-retryable exceptions: `IllegalArgumentException`, `NumberFormatException`, `ClassCastException`, `DeserializationException`

### 4. Topic Configuration

**Standardized Topic Naming Convention**:
- All topics prefixed with `smm.`
- Consistent naming: `smm.{service}.{action}`
- DLQ topics: `smm.{service}.{action}.dlq`

**Topic Configuration**:
```java
@Bean
public NewTopic orderProcessingTopic() {
    return TopicBuilder.name("smm.order.processing")
            .partitions(3)
            .replicas(1)
            .configs(Map.of(
                "retention.ms", "604800000", // 7 days
                "cleanup.policy", "delete",
                "compression.type", "snappy"
            ))
            .build();
}
```

### 5. Health Monitoring

**Kafka Health Indicator**:
- Created `KafkaHealthIndicator.java`
- Monitors Kafka connectivity
- Checks for required SMM topics
- Provides detailed health status via `/actuator/health`

**Health Check Features**:
- Connection validation
- Topic existence verification
- Detailed error reporting
- Integration with Spring Boot Actuator

### 6. Producer Configuration

**Enhanced Producer Settings**:
```java
configProps.put(ProducerConfig.ACKS_CONFIG, "all");
configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
```

### 7. Consumer Configuration

**Enhanced Consumer Settings**:
```java
configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
```

## Files Modified

### Configuration Files
1. `KafkaConfig.java` - Main Kafka configuration
2. `KafkaErrorHandlerConfig.java` - Error handling configuration
3. `KafkaHealthIndicator.java` - Health monitoring
4. `application.yml` - Updated serialization configuration

### Service Files
1. `OrderService.java` - Fixed topic names
2. `VideoProcessingService.java` - Fixed topic names
3. `YouTubeAutomationService.java` - Fixed topic names
4. `OrderProcessingService.java` - Fixed topic names
5. `NotificationService.java` - Fixed topic names
6. `KafkaProducers.java` - Fixed topic names

## Benefits

1. **Reliability**: Proper error handling with DLQ ensures no message loss
2. **Consistency**: Standardized topic naming across all services
3. **Monitoring**: Health checks provide visibility into Kafka status
4. **Performance**: Optimized producer/consumer configurations
5. **Maintainability**: Centralized error handling and configuration
6. **Production Ready**: Idempotence, compression, and proper serialization

## Testing

To verify the configuration:

1. **Health Check**: `GET /actuator/health`
2. **Topic Verification**: Check that all topics are created with correct names
3. **Error Handling**: Send malformed messages to verify DLQ functionality
4. **Serialization**: Verify proper JSON serialization/deserialization

## Monitoring

- Kafka health is available at `/actuator/health`
- DLQ topics can be monitored for failed messages
- Application logs include detailed Kafka error information
- Metrics are available via Spring Boot Actuator 