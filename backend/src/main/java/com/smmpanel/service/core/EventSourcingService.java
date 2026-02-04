package com.smmpanel.service.core;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderEvent;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderEventRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSourcingService {

    private final OrderEventRepository orderEventRepository;
    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final int MAX_RETRY_COUNT = 3;

    @Transactional
    public void saveOrderEvent(Order order, String eventType, Map<String, Object> eventData) {
        saveEvent(order.getId(), eventType, eventData, null, null);
    }

    @Transactional
    public OrderEvent saveEvent(
            Long orderId,
            String eventType,
            Map<String, Object> eventData,
            String correlationId,
            String causationId) {

        // Check for duplicate events
        String eventId = UUID.randomUUID().toString();
        if (orderEventRepository.existsByEventId(eventId)) {
            log.warn("Duplicate event detected: {}", eventId);
            return orderEventRepository.findByEventId(eventId).orElse(null);
        }

        // Get order created_at for composite foreign key
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Get next sequence number
        Long sequenceNumber =
                orderEventRepository
                        .findMaxSequenceNumberForOrder(orderId)
                        .map(seq -> seq + 1)
                        .orElse(1L);

        // Build event
        OrderEvent event =
                OrderEvent.builder()
                        .eventId(eventId)
                        .aggregateId("ORDER-" + orderId)
                        .orderId(orderId)
                        .orderCreatedAt(order.getCreatedAt())
                        .eventType(eventType)
                        .sequenceNumber(sequenceNumber)
                        .eventData(eventData)
                        .correlationId(
                                correlationId != null
                                        ? correlationId
                                        : UUID.randomUUID().toString())
                        .causationId(causationId)
                        .metadata(buildMetadata())
                        .build();

        // Save to event store
        event = orderEventRepository.save(event);

        // Publish to Kafka asynchronously
        publishEventToKafka(event);

        log.info(
                "Event saved: orderId={}, type={}, sequence={}",
                orderId,
                eventType,
                sequenceNumber);

        return event;
    }

    @Transactional(readOnly = true)
    public List<OrderEvent> getOrderEvents(Long orderId) {
        return orderEventRepository.findByOrderIdOrderBySequenceNumber(orderId);
    }

    @Transactional(readOnly = true)
    public Order rebuildOrderFromEvents(Long orderId) {
        List<OrderEvent> events = getOrderEvents(orderId);

        if (events.isEmpty()) {
            log.warn("No events found for order: {}", orderId);
            return null;
        }

        // Start with empty order state
        Order order = new Order();
        order.setId(orderId);

        // Apply each event in sequence
        for (OrderEvent event : events) {
            applyEventToOrder(order, event);
        }

        log.info("Order rebuilt from {} events: orderId={}", events.size(), orderId);
        return order;
    }

    @Transactional
    public void replayEvents(Long orderId, Long fromSequence) {
        List<OrderEvent> events =
                orderEventRepository.findEventsAfterSequence(orderId, fromSequence);

        log.info(
                "Replaying {} events for order {} from sequence {}",
                events.size(),
                orderId,
                fromSequence);

        for (OrderEvent event : events) {
            // Mark as unprocessed for replay
            event.setProcessed(false);
            event.setRetryCount(0);
            orderEventRepository.save(event);

            // Re-publish to Kafka
            publishEventToKafka(event);
        }
    }

    @Transactional
    public void replayEventsInTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        List<OrderEvent> events =
                orderEventRepository.findEventsBetweenTimestamps(startTime, endTime);

        log.info("Replaying {} events between {} and {}", events.size(), startTime, endTime);

        for (OrderEvent event : events) {
            publishEventToKafka(event);
        }
    }

    @Transactional
    public void processUnprocessedEvents() {
        List<OrderEvent> unprocessedEvents =
                orderEventRepository.findUnprocessedEvents(
                        MAX_RETRY_COUNT, org.springframework.data.domain.PageRequest.of(0, 100));

        log.info("Processing {} unprocessed events", unprocessedEvents.size());

        for (OrderEvent event : unprocessedEvents) {
            try {
                // Re-publish to Kafka
                publishEventToKafka(event);

                // Mark as processed
                event.markAsProcessed();
                orderEventRepository.save(event);

            } catch (Exception e) {
                log.error("Failed to process event: {}", event.getEventId(), e);

                // Increment retry count
                event.incrementRetryCount();
                event.setErrorMessage(e.getMessage());
                orderEventRepository.save(event);
            }
        }
    }

    @Transactional
    public Map<String, Object> getEventStatistics(Long orderId) {
        Map<String, Object> stats = new HashMap<>();

        List<OrderEvent> events = getOrderEvents(orderId);

        stats.put("totalEvents", events.size());
        stats.put("processedEvents", events.stream().filter(OrderEvent::isProcessed).count());
        stats.put("failedEvents", events.stream().filter(e -> e.getRetryCount() > 0).count());

        // Group by event type
        Map<String, Long> eventTypeCounts = new HashMap<>();
        for (OrderEvent event : events) {
            eventTypeCounts.merge(event.getEventType(), 1L, Long::sum);
        }
        stats.put("eventTypeCounts", eventTypeCounts);

        // Get timeline
        if (!events.isEmpty()) {
            stats.put("firstEventTime", events.get(0).getEventTimestamp());
            stats.put("lastEventTime", events.get(events.size() - 1).getEventTimestamp());
        }

        return stats;
    }

    @Transactional
    public void cleanupOldProcessedEvents(LocalDateTime cutoffTime) {
        List<OrderEvent> staleEvents = orderEventRepository.findStaleUnprocessedEvents(cutoffTime);

        for (OrderEvent event : staleEvents) {
            if (event.getRetryCount() >= MAX_RETRY_COUNT) {
                log.warn("Marking stale event as failed: {}", event.getEventId());
                event.setErrorMessage("Max retries exceeded - marked as stale");
                event.markAsProcessed();
                orderEventRepository.save(event);
            }
        }

        log.info("Cleaned up {} stale events", staleEvents.size());
    }

    private void publishEventToKafka(OrderEvent event) {
        String topic = determineTopicForEvent(event.getEventType());

        CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>> future =
                kafkaTemplate.send(topic, event.getAggregateId(), event);

        future.whenComplete(
                (result, ex) -> {
                    if (ex == null) {
                        // Update event with Kafka metadata
                        event.setKafkaTopic(topic);
                        event.setKafkaPartition(result.getRecordMetadata().partition());
                        event.setKafkaOffset(result.getRecordMetadata().offset());
                        orderEventRepository.save(event);

                        log.debug(
                                "Event published to Kafka: topic={}, partition={}, offset={}",
                                topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to publish event to Kafka: {}", event.getEventId(), ex);
                    }
                });
    }

    private String determineTopicForEvent(String eventType) {
        switch (eventType) {
            case OrderEvent.ORDER_CREATED:
            case OrderEvent.ORDER_VALIDATED:
                return "smm.order.processing";
            case OrderEvent.ORDER_STATUS_CHANGED:
                return "smm.order.state.updates";
            case OrderEvent.ORDER_REFUNDED:
                return "smm.order.refund";
            default:
                return "smm.order.processing";
        }
    }

    private void applyEventToOrder(Order order, OrderEvent event) {
        Map<String, Object> data = event.getEventData();

        switch (event.getEventType()) {
            case OrderEvent.ORDER_CREATED:
                order.setQuantity((Integer) data.get("quantity"));
                order.setLink((String) data.get("link"));
                order.setStatus(OrderStatus.PENDING);
                break;

            case OrderEvent.ORDER_STATUS_CHANGED:
                String newStatus = (String) data.get("newStatus");
                order.setStatus(OrderStatus.valueOf(newStatus));
                break;

            case OrderEvent.ORDER_COMPLETED:
                order.setStatus(OrderStatus.COMPLETED);
                order.setRemains(0);
                break;

            case OrderEvent.ORDER_CANCELLED:
                order.setStatus(OrderStatus.CANCELLED);
                // CRITICAL: Set remains to full quantity if not specified in event data
                if (data != null && data.containsKey("remains")) {
                    order.setRemains(((Number) data.get("remains")).intValue());
                } else {
                    order.setRemains(order.getQuantity());
                }
                break;

            default:
                log.debug("Unknown event type for order rebuild: {}", event.getEventType());
        }
    }

    private Map<String, Object> buildMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("timestamp", LocalDateTime.now().toString());
        metadata.put("service", "EventSourcingService");
        metadata.put("version", "1.0");
        return metadata;
    }

    public boolean isEventDuplicate(String eventId) {
        return orderEventRepository.existsByEventId(eventId);
    }
}
