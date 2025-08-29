package com.smmpanel.service.order.state;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderStatusChangedEvent;
import com.smmpanel.exception.IllegalOrderStateTransitionException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.AuditService;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateManager {

    private final OrderRepository orderRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    // Valid state transitions
    private static final Map<OrderStatus, Set<OrderStatus>> VALID_TRANSITIONS =
            new EnumMap<>(OrderStatus.class);

    static {
        VALID_TRANSITIONS.put(
                OrderStatus.PENDING, EnumSet.of(OrderStatus.IN_PROGRESS, OrderStatus.CANCELLED));

        VALID_TRANSITIONS.put(
                OrderStatus.IN_PROGRESS,
                EnumSet.of(
                        OrderStatus.PROCESSING,
                        OrderStatus.ACTIVE,
                        OrderStatus.CANCELLED,
                        OrderStatus.HOLDING));

        VALID_TRANSITIONS.put(
                OrderStatus.PROCESSING,
                EnumSet.of(OrderStatus.ACTIVE, OrderStatus.CANCELLED, OrderStatus.HOLDING));

        VALID_TRANSITIONS.put(
                OrderStatus.ACTIVE,
                EnumSet.of(
                        OrderStatus.COMPLETED,
                        OrderStatus.PARTIAL,
                        OrderStatus.PAUSED,
                        OrderStatus.HOLDING));

        VALID_TRANSITIONS.put(
                OrderStatus.PARTIAL, EnumSet.of(OrderStatus.COMPLETED, OrderStatus.HOLDING));

        VALID_TRANSITIONS.put(
                OrderStatus.PAUSED, EnumSet.of(OrderStatus.ACTIVE, OrderStatus.CANCELLED));

        VALID_TRANSITIONS.put(
                OrderStatus.HOLDING,
                EnumSet.of(OrderStatus.ACTIVE, OrderStatus.CANCELLED, OrderStatus.PROCESSING));

        VALID_TRANSITIONS.put(OrderStatus.COMPLETED, EnumSet.of(OrderStatus.REFILL));

        VALID_TRANSITIONS.put(
                OrderStatus.REFILL, EnumSet.of(OrderStatus.ACTIVE, OrderStatus.COMPLETED));

        // CANCELLED is a terminal state with no valid transitions
        VALID_TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    @Transactional
    public void transitionTo(Order order, OrderStatus newStatus) {
        OrderStatus currentStatus = order.getStatus();

        if (!isValidTransition(currentStatus, newStatus)) {
            throw new IllegalOrderStateTransitionException(
                    String.format(
                            "Invalid state transition from %s to %s for order %d",
                            currentStatus, newStatus, order.getId()));
        }

        // Save the old status before updating
        OrderStatus oldStatus = order.getStatus();

        // Update the order status
        order.setStatus(newStatus);
        order = orderRepository.save(order);

        // Log the state transition
        logStateTransition(order.getId(), oldStatus, newStatus);

        // Publish the status change event
        publishStatusChangeEvent(order, oldStatus, newStatus);
    }

    private boolean isValidTransition(OrderStatus from, OrderStatus to) {
        if (from == to) {
            return true; // Same state is always valid
        }

        Set<OrderStatus> validTargets = VALID_TRANSITIONS.get(from);
        return validTargets != null && validTargets.contains(to);
    }

    private void logStateTransition(Long orderId, OrderStatus fromStatus, OrderStatus toStatus) {
        String message =
                String.format("Order %d state changed: %s -> %s", orderId, fromStatus, toStatus);

        log.info(message);

        // Log to audit trail - method not available in AuditService
    }

    private void publishStatusChangeEvent(
            Order order, OrderStatus oldStatus, OrderStatus newStatus) {
        try {
            eventPublisher.publishEvent(new OrderStatusChangedEvent(order, oldStatus, newStatus));
        } catch (Exception e) {
            log.error(
                    "Failed to publish status change event for order {}: {}",
                    order.getId(),
                    e.getMessage(),
                    e);
            // Don't rethrow to avoid affecting the main transaction
        }
    }
}
