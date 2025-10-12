package com.smmpanel.producer;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.event.OrderStatusChangedEvent;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/** Order Event Producer Publishes order events to Kafka for async processing */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String ORDER_PROCESSING_TOPIC = "smm.order.processing";
    private static final String ORDER_STATUS_UPDATES_TOPIC = "smm.order.state.updates";

    /** Publish order created event */
    public CompletableFuture<SendResult<String, Object>> publishOrderCreatedEvent(
            OrderCreatedEvent event) {
        String key = "order-" + event.getOrderId();

        log.info(
                "Publishing order created event: orderId={}, userId={}",
                event.getOrderId(),
                event.getUserId());

        return kafkaTemplate
                .send(ORDER_PROCESSING_TOPIC, key, event)
                .whenComplete(
                        (result, throwable) -> {
                            if (throwable != null) {
                                log.error(
                                        "Failed to publish order created event: orderId={}",
                                        event.getOrderId(),
                                        throwable);
                            } else {
                                log.debug(
                                        "Successfully published order created event: orderId={},"
                                                + " partition={}, offset={}",
                                        event.getOrderId(),
                                        result.getRecordMetadata().partition(),
                                        result.getRecordMetadata().offset());
                            }
                        });
    }

    /** Publish order status changed event */
    public CompletableFuture<SendResult<String, Object>> publishOrderStatusChangedEvent(
            OrderStatusChangedEvent event) {
        String key = "order-status-" + event.getOrder().getId();

        log.info(
                "Publishing order status changed event: orderId={}, oldStatus={}, newStatus={}",
                event.getOrder().getId(),
                event.getOldStatus(),
                event.getNewStatus());

        return kafkaTemplate
                .send(ORDER_STATUS_UPDATES_TOPIC, key, event)
                .whenComplete(
                        (result, throwable) -> {
                            if (throwable != null) {
                                log.error(
                                        "Failed to publish order status changed event: orderId={}",
                                        event.getOrder().getId(),
                                        throwable);
                            } else {
                                log.debug(
                                        "Successfully published order status changed event:"
                                                + " orderId={}, partition={}, offset={}",
                                        event.getOrder().getId(),
                                        result.getRecordMetadata().partition(),
                                        result.getRecordMetadata().offset());
                            }
                        });
    }

    /** Publish order processing event with correlation ID */
    public CompletableFuture<SendResult<String, Object>> publishOrderProcessingEvent(
            Long orderId, Long userId, String correlationId) {
        String key = "order-" + orderId;

        OrderProcessingEvent event =
                OrderProcessingEvent.builder()
                        .orderId(orderId)
                        .userId(userId)
                        .correlationId(correlationId)
                        .timestamp(System.currentTimeMillis())
                        .build();

        log.info(
                "Publishing order processing event: orderId={}, userId={}, correlationId={}",
                orderId,
                userId,
                correlationId);

        return kafkaTemplate
                .send(ORDER_PROCESSING_TOPIC, key, event)
                .whenComplete(
                        (result, throwable) -> {
                            if (throwable != null) {
                                log.error(
                                        "Failed to publish order processing event: orderId={}",
                                        orderId,
                                        throwable);
                            } else {
                                log.debug(
                                        "Successfully published order processing event: orderId={},"
                                                + " partition={}, offset={}",
                                        orderId,
                                        result.getRecordMetadata().partition(),
                                        result.getRecordMetadata().offset());
                            }
                        });
    }

    /** Order Processing Event DTO */
    public static class OrderProcessingEvent {
        @JsonProperty("orderId")
        private Long orderId;

        @JsonProperty("userId")
        private Long userId;

        @JsonProperty("correlationId")
        private String correlationId;

        @JsonProperty("timestamp")
        private Long timestamp;

        public OrderProcessingEvent() {}

        public OrderProcessingEvent(
                Long orderId, Long userId, String correlationId, Long timestamp) {
            this.orderId = orderId;
            this.userId = userId;
            this.correlationId = correlationId;
            this.timestamp = timestamp;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public String getCorrelationId() {
            return correlationId;
        }

        public void setCorrelationId(String correlationId) {
            this.correlationId = correlationId;
        }

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }

        public static class Builder {
            private Long orderId;
            private Long userId;
            private String correlationId;
            private Long timestamp;

            public Builder orderId(Long orderId) {
                this.orderId = orderId;
                return this;
            }

            public Builder userId(Long userId) {
                this.userId = userId;
                return this;
            }

            public Builder correlationId(String correlationId) {
                this.correlationId = correlationId;
                return this;
            }

            public Builder timestamp(Long timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public OrderProcessingEvent build() {
                return new OrderProcessingEvent(orderId, userId, correlationId, timestamp);
            }
        }
    }
}
