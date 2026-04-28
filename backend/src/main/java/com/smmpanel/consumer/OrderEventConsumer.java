package com.smmpanel.consumer;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.event.OrderStatusChangedEvent;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.MessageProcessingService;
import com.smmpanel.service.integration.InstagramService;
import com.smmpanel.service.kafka.MessageIdempotencyService;
import com.smmpanel.service.order.OrderStateManagementService;
import java.math.BigDecimal;
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
    private final InstagramService instagramService;
    private final BalanceService balanceService;

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

    /** Process Instagram order - send to Instagram bot with retry support */
    private void processInstagramOrder(Order order) {
        log.info("Processing Instagram order {} via Kafka consumer", order.getId());

        try {
            var instagramResponse = instagramService.createInstagramOrder(order);

            if (instagramResponse.isSuccess()) {
                // Save bot's order ID for webhook correlation and set status
                order.setInstagramBotOrderId(instagramResponse.getId());
                order.setStatus(OrderStatus.IN_PROGRESS);
                orderRepository.save(order);

                log.info(
                        "Instagram order {} successfully sent to bot, botOrderId: {}",
                        order.getId(),
                        instagramResponse.getId());
            } else {
                log.error(
                        "Instagram bot returned error for order {}: {}",
                        order.getId(),
                        instagramResponse.getError());

                // Mark as cancelled and refund
                order.setStatus(OrderStatus.CANCELLED);
                order.setErrorMessage("Instagram bot error: " + instagramResponse.getError());
                // CRITICAL: Set remains to full quantity (nothing delivered)
                order.setRemains(order.getQuantity());

                // Refund user
                BigDecimal refundAmount = order.getCharge();
                balanceService.refund(
                        order.getUser(),
                        refundAmount,
                        order,
                        "Refund for failed Instagram order #" + order.getId());

                // CRITICAL: Set charge to 0 after full refund
                order.setCharge(BigDecimal.ZERO);
                orderRepository.save(order);

                log.info(
                        "Refunded {} to user for failed Instagram order {}",
                        refundAmount,
                        order.getId());
            }
        } catch (Exception e) {
            log.error("Failed to process Instagram order {}: {}", order.getId(), e.getMessage(), e);

            // Re-throw to trigger Kafka retry mechanism
            throw new RuntimeException("Instagram order processing failed: " + e.getMessage(), e);
        }
    }
}
