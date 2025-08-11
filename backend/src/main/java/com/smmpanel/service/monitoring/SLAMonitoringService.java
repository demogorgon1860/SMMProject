package com.smmpanel.service.monitoring;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderStatusChangedEvent;
import com.smmpanel.repository.jpa.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SLAMonitoringService {

    private final OrderRepository orderRepository;

    @Qualifier("monitoringAlertService") private final AlertService alertService;

    private final MeterRegistry meterRegistry;

    // SLA thresholds
    private static final Duration ORDER_PROCESSING_SLA = Duration.ofMinutes(5);
    private static final Duration ORDER_COMPLETION_SLA = Duration.ofHours(24);
    private static final double SUCCESS_RATE_THRESHOLD = 0.99; // 99%
    private static final int SUSPICIOUS_ORDERS_THRESHOLD = 20; // Orders per hour

    @Scheduled(fixedDelayString = "${app.sla.monitoring.interval:60000}") // Default: 1 minute
    public void monitorOrderProcessingSLA() {
        LocalDateTime threshold = LocalDateTime.now().minus(ORDER_PROCESSING_SLA);

        List<Order> delayedOrders =
                orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, threshold);

        if (!delayedOrders.isEmpty()) {
            alertService.sendAlert(
                    AlertLevel.WARNING,
                    String.format(
                            "%d orders exceeding processing SLA (%d minutes)",
                            delayedOrders.size(), ORDER_PROCESSING_SLA.toMinutes()),
                    Map.of(
                            "order_ids",
                            delayedOrders.stream().map(Order::getId).collect(Collectors.toList())));

            // Update metrics
            meterRegistry.gauge("orders.sla.processing.violations", delayedOrders.size());
        }
    }

    @Scheduled(fixedDelayString = "${app.sla.completion.interval:300000}") // Default: 5 minutes
    public void monitorOrderCompletionSLA() {
        LocalDateTime threshold = LocalDateTime.now().minus(ORDER_COMPLETION_SLA);

        List<Order> delayedActiveOrders =
                orderRepository.findByStatusInAndCreatedAtBefore(
                        List.of(
                                OrderStatus.ACTIVE,
                                OrderStatus.PROCESSING,
                                OrderStatus.IN_PROGRESS),
                        threshold);

        if (!delayedActiveOrders.isEmpty()) {
            alertService.sendAlert(
                    AlertLevel.CRITICAL,
                    String.format(
                            "%d orders exceeding completion SLA (%d hours)",
                            delayedActiveOrders.size(), ORDER_COMPLETION_SLA.toHours()),
                    Map.of(
                            "order_ids",
                            delayedActiveOrders.stream()
                                    .map(Order::getId)
                                    .collect(Collectors.toList())));

            // Auto-escalate long-delayed orders
            delayedActiveOrders.stream()
                    .filter(
                            order ->
                                    order.getCreatedAt()
                                            .isBefore(LocalDateTime.now().minusHours(48)))
                    .forEach(this::escalateOrder);
        }
    }

    @Scheduled(fixedDelayString = "${app.sla.success-rate.interval:3600000}") // Default: 1 hour
    public void monitorSuccessRate() {
        LocalDateTime since = LocalDateTime.now().minusHours(1);

        long totalOrders = orderRepository.countByCreatedAtAfter(since);
        long successfulOrders =
                orderRepository.countByStatusAndCreatedAtAfter(OrderStatus.COMPLETED, since);

        if (totalOrders > 0) {
            double successRate = (double) successfulOrders / totalOrders;
            meterRegistry.gauge("orders.success.rate", successRate);

            if (successRate < SUCCESS_RATE_THRESHOLD) {
                alertService.sendAlert(
                        AlertLevel.CRITICAL,
                        String.format(
                                "Order success rate below threshold: %.2f%% (threshold: %.2f%%)",
                                successRate * 100, SUCCESS_RATE_THRESHOLD * 100),
                        Map.of(
                                "total_orders", totalOrders,
                                "successful_orders", successfulOrders,
                                "success_rate", successRate));
            }
        }
    }

    @EventListener
    public void handleOrderStatusChange(OrderStatusChangedEvent event) {
        Order order = event.getOrder();

        // Calculate processing time metrics
        if (event.getNewStatus() == OrderStatus.ACTIVE) {
            Duration processingTime = Duration.between(order.getCreatedAt(), LocalDateTime.now());

            meterRegistry.timer("orders.processing.time").record(processingTime);

            if (processingTime.compareTo(ORDER_PROCESSING_SLA) > 0) {
                meterRegistry.counter("orders.sla.processing.violations").increment();
            }
        }

        // Calculate completion time metrics
        if (event.getNewStatus() == OrderStatus.COMPLETED) {
            Duration completionTime = Duration.between(order.getCreatedAt(), LocalDateTime.now());

            meterRegistry.timer("orders.completion.time").record(completionTime);

            if (completionTime.compareTo(ORDER_COMPLETION_SLA) > 0) {
                meterRegistry.counter("orders.sla.completion.violations").increment();
            }
        }

        // Check for suspicious order patterns
        checkForSuspiciousPatterns(order);
    }

    private void checkForSuspiciousPatterns(Order order) {
        // Check for too many orders from the same user in a short time
        LocalDateTime lastHour = LocalDateTime.now().minusHours(1);
        long userOrderCount =
                orderRepository.countByUserIdAndCreatedAtAfter(order.getUser().getId(), lastHour);

        if (userOrderCount > SUSPICIOUS_ORDERS_THRESHOLD) {
            alertService.sendAlert(
                    AlertLevel.WARNING,
                    String.format(
                            "User %d has placed %d orders in the last hour",
                            order.getUser().getId(), userOrderCount),
                    Map.of(
                            "user_id", order.getUser().getId(),
                            "order_count", userOrderCount,
                            "last_order_id", order.getId()));
        }
    }

    private void escalateOrder(Order order) {
        try {
            order.setProcessingPriority(order.getProcessingPriority() + 10); // Increase priority
            orderRepository.save(order);

            alertService.sendAlert(
                    AlertLevel.CRITICAL,
                    String.format(
                            "Order %d escalated due to extended delay (%.1f hours)",
                            order.getId(),
                            Duration.between(order.getCreatedAt(), LocalDateTime.now()).toHours()),
                    Map.of(
                            "order_id", order.getId(),
                            "status", order.getStatus(),
                            "hours_since_creation",
                                    Duration.between(order.getCreatedAt(), LocalDateTime.now())
                                            .toHours()));

            log.warn("Order {} escalated due to extended delay", order.getId());

        } catch (Exception e) {
            log.error("Failed to escalate order {}: {}", order.getId(), e.getMessage(), e);
        }
    }
}
