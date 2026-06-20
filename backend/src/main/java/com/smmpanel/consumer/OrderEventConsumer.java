package com.smmpanel.consumer;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.event.OrderStatusChangedEvent;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.core.MessageProcessingService;
import com.smmpanel.service.kafka.MessageIdempotencyService;
import com.smmpanel.service.order.OrderSerializationService;
import com.smmpanel.service.order.OrderStateManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Order Event Consumer Processes order events from Kafka asynchronously */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderRepository orderRepository;
    private final OrderStateManagementService orderStateManagementService;
    private final OrderEventProducer orderEventProducer;
    private final MessageProcessingService messageProcessingService;
    private final MessageIdempotencyService deduplicationService;
    private final OrderSerializationService orderSerializationService;

    /** Process order created events with RetryTopic and DLT */
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            autoCreateTopics = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.FAIL_ON_ERROR,
            concurrency = "2")
    @KafkaListener(topics = "smm.order.processing", groupId = "smm-order-processing-group")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOrderCreatedEvent(
            @Payload OrderCreatedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        // Generate unique message ID for idempotency
        String messageId = deduplicationService.generateMessageId(topic, partition, offset);
        Long orderId = event.getOrderId();

        log.info(
                "Processing order created event: orderId={}, userId={}, topic={},"
                        + " partition={}, offset={}",
                orderId,
                event.getUserId(),
                topic,
                partition,
                offset);

        try {
            // Check for duplicate processing
            if (deduplicationService.isOrderEventAlreadyProcessed(messageId, orderId)) {
                log.warn(
                        "Duplicate order event detected, skipping: orderId={}, messageId={}",
                        orderId,
                        messageId);
                return;
            }

            // Process the order
            processOrderCreatedEventInternal(event);

            // Mark as processed after successful processing
            deduplicationService.markOrderEventAsProcessed(messageId, orderId);

            log.info("Successfully processed order created event: orderId={}", orderId);
        } catch (Exception e) {
            log.error("Failed to process order created event: orderId={}", orderId, e);
            throw e; // Let RetryTopic handle retries and DLT
        }
    }

    /** Process order status changed events */
    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            autoCreateTopics = "true",
            concurrency = "2")
    @KafkaListener(topics = "smm.order.state.updates", groupId = "order-status-group")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOrderStatusChangedEvent(
            @Payload OrderStatusChangedEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        // Generate unique message ID for idempotency
        String messageId = deduplicationService.generateMessageId(topic, partition, offset);
        Long orderId = event.getOrder().getId();

        log.info(
                "Processing order status changed event: orderId={}, oldStatus={}, newStatus={},"
                        + " topic={}, partition={}, offset={}",
                orderId,
                event.getOldStatus(),
                event.getNewStatus(),
                topic,
                partition,
                offset);

        try {
            // Check for duplicate processing
            if (deduplicationService.isOrderEventAlreadyProcessed(messageId, orderId)) {
                log.warn(
                        "Duplicate order status event detected, skipping: orderId={}, messageId={}",
                        orderId,
                        messageId);
                return;
            }
            // Handle status-specific logic
            switch (event.getNewStatus()) {
                case ACTIVE:
                    handleOrderActivated(event.getOrder());
                    break;
                case COMPLETED:
                    handleOrderCompleted(event.getOrder());
                    break;
                case CANCELLED:
                    handleOrderCancelled(event.getOrder());
                    break;
                default:
                    log.debug("No specific handling for status: {}", event.getNewStatus());
            }

            // Mark as processed after successful handling
            deduplicationService.markOrderEventAsProcessed(messageId, orderId);

            log.info(
                    "Successfully processed order status changed event: orderId={}, newStatus={}",
                    orderId,
                    event.getNewStatus());

        } catch (Exception e) {
            log.error("Failed to process order status changed event: orderId={}", orderId, e);
            throw e; // Let Kafka retry or send to DLQ
        }
    }

    /** Update order status */
    private void updateOrderStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        orderRepository.save(order);

        log.info(
                "Order status updated: orderId={}, oldStatus={}, newStatus={}",
                order.getId(),
                oldStatus,
                newStatus);
    }

    /** Handle order activated */
    private void handleOrderActivated(Order order) {
        log.info("Order activated: orderId={}", order.getId());
    }

    /** Handle order completed */
    private void handleOrderCompleted(Order order) {
        log.info("Order completed: orderId={}", order.getId());
    }

    /** Handle order cancelled */
    private void handleOrderCancelled(Order order) {
        log.info("Order cancelled: orderId={}", order.getId());
    }

    /** Generate unique message ID for idempotency */
    private String generateMessageId(String eventType, Long orderId, long timestamp) {
        return String.format("%s-%d-%d", eventType, orderId, timestamp);
    }

    /** Internal processing method for order created events */
    private void processOrderCreatedEventInternal(OrderCreatedEvent event) {
        Order order =
                orderRepository
                        .findByIdWithAllDetails(event.getOrderId())
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "Order not found: " + event.getOrderId()));

        // Skip processing if order is not in PENDING status
        if (!OrderStatus.PENDING.equals(order.getStatus())) {
            log.warn(
                    "Skipping order processing - order {} not in PENDING status: {}",
                    order.getId(),
                    order.getStatus());
            return;
        }

        // All orders are Instagram orders now (panel pivoted to Instagram-only)
        processInstagramOrder(order);
    }

    /**
     * Hand the order to the per-URL serialization gate. When enabled, {@code pumpUrl} dispatches
     * the lowest-id PENDING order for this link only if the URL is free — so this order may be sent
     * now or left PENDING (an earlier same-link order goes first, or one is already active), and
     * the completion pump / sweeper releases it later. When disabled, dispatch immediately (legacy
     * behavior). Exceptions propagate so the Kafka RetryTopic re-runs the (idempotent) pump. The
     * actual bot dispatch + cancel/refund-on-error now lives in {@code
     * OrderSerializationService.dispatchOrderToBot} (extracted verbatim).
     */
    private void processInstagramOrder(Order order) {
        log.info("Processing Instagram order {} via Kafka consumer", order.getId());
        if (orderSerializationService.isEnabled()) {
            orderSerializationService.pumpUrl(order.getLink());
        } else {
            orderSerializationService.dispatchOrderToBot(order);
        }
    }
}
