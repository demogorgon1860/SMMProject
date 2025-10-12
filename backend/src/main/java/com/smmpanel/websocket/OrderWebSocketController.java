package com.smmpanel.websocket;

import com.smmpanel.dto.websocket.*;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.service.order.OrderService;
import java.security.Principal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/**
 * WebSocket Controller for real-time order updates Handles order status updates, progress tracking,
 * and notifications
 */
@Slf4j
@Controller
public class OrderWebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final OrderService orderService;

    public OrderWebSocketController(
            SimpMessagingTemplate messagingTemplate, @Lazy OrderService orderService) {
        this.messagingTemplate = messagingTemplate;
        this.orderService = orderService;
    }

    // Track active subscriptions
    private final Map<String, String> activeSubscriptions = new ConcurrentHashMap<>();

    /** Subscribe to order updates for a specific order */
    @SubscribeMapping("/orders/{orderId}")
    public OrderUpdateMessage subscribeToOrder(
            @DestinationVariable Long orderId, Principal principal) {
        log.info("User {} subscribed to order {}", principal.getName(), orderId);

        // Track subscription
        activeSubscriptions.put(
                principal.getName() + ":" + orderId, LocalDateTime.now().toString());

        // Return current order status
        Order order = orderService.getOrderById(orderId);

        return OrderUpdateMessage.builder()
                .orderId(orderId)
                .status(order.getStatus().name())
                .progress(calculateProgress(order))
                .message("Subscribed to order updates")
                .timestamp(LocalDateTime.now())
                .build();
    }

    /** Send order update to specific user */
    @MessageMapping("/order.update")
    @SendToUser("/queue/orders")
    public OrderUpdateMessage sendOrderUpdate(
            @Payload OrderUpdateRequest request, Principal principal) {
        log.debug(
                "Sending order update for order {} to user {}",
                request.getOrderId(),
                principal.getName());

        return OrderUpdateMessage.builder()
                .orderId(request.getOrderId())
                .status(request.getStatus())
                .progress(request.getProgress())
                .message(request.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /** Broadcast order status change to all subscribers */
    public void broadcastOrderStatusChange(Long orderId, OrderStatus newStatus, String message) {
        Order order = orderService.getOrderById(orderId);

        OrderUpdateMessage update =
                OrderUpdateMessage.builder()
                        .orderId(orderId)
                        .status(newStatus.name())
                        .progress(calculateProgress(order))
                        .message(message)
                        .timestamp(LocalDateTime.now())
                        .metadata(
                                Map.of(
                                        "previousStatus", order.getStatus().name(),
                                        "quantity", order.getQuantity(),
                                        "completed",
                                                order.getQuantity()
                                                        - (order.getRemains() != null
                                                                ? order.getRemains()
                                                                : 0)))
                        .build();

        // Send to order-specific topic
        messagingTemplate.convertAndSend("/topic/orders/" + orderId, update);

        // Send to user queue
        messagingTemplate.convertAndSendToUser(
                order.getUser().getUsername(), "/queue/orders", update);

        log.info("Broadcasted order {} status change to {}", orderId, newStatus);
    }

    /** Send progress update for an order */
    public void sendOrderProgress(Long orderId, int completedQuantity, int totalQuantity) {
        Order order = orderService.getOrderById(orderId);

        double progress = (double) completedQuantity / totalQuantity * 100;

        OrderProgressMessage progressUpdate =
                OrderProgressMessage.builder()
                        .orderId(orderId)
                        .completedQuantity(completedQuantity)
                        .totalQuantity(totalQuantity)
                        .progressPercentage(progress)
                        .estimatedCompletion(estimateCompletion(order, progress))
                        .timestamp(LocalDateTime.now())
                        .build();

        // Broadcast to subscribers
        messagingTemplate.convertAndSend("/topic/orders/" + orderId + "/progress", progressUpdate);

        // Send to user
        messagingTemplate.convertAndSendToUser(
                order.getUser().getUsername(), "/queue/orders/progress", progressUpdate);
    }

    /** Send progress update for an order by ID only */
    public void sendProgressUpdate(Long orderId) {
        try {
            Order order = orderService.getOrderById(orderId);
            if (order != null) {
                sendOrderProgress(
                        orderId,
                        order.getQuantity() - (order.getRemains() != null ? order.getRemains() : 0),
                        order.getQuantity());
            }
        } catch (Exception e) {
            log.error("Failed to send progress update for order {}: {}", orderId, e.getMessage());
        }
    }

    /** Send notification to user */
    public void sendUserNotification(String username, String type, String title, String message) {
        NotificationMessage notification =
                NotificationMessage.builder()
                        .type(type)
                        .title(title)
                        .message(message)
                        .timestamp(LocalDateTime.now())
                        .build();

        messagingTemplate.convertAndSendToUser(username, "/queue/notifications", notification);

        log.debug("Sent notification to user {}: {}", username, title);
    }

    /** Broadcast system-wide announcement */
    @MessageMapping("/broadcast.announcement")
    @SendTo("/topic/announcements")
    public SystemMessage broadcastAnnouncement(
            @Payload SystemMessage message, Authentication auth) {
        // Only admins can broadcast
        if (!auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new SecurityException("Only admins can broadcast announcements");
        }

        log.info("Admin {} broadcasting: {}", auth.getName(), message.getMessage());

        return SystemMessage.builder()
                .type("ANNOUNCEMENT")
                .message(message.getMessage())
                .severity(message.getSeverity())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /** Get active WebSocket connections count */
    @MessageMapping("/stats.connections")
    @SendTo("/topic/stats")
    public Map<String, Object> getConnectionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeConnections", activeSubscriptions.size());
        stats.put("timestamp", LocalDateTime.now());

        return stats;
    }

    /** Periodic heartbeat to keep connections alive */
    @Scheduled(fixedDelay = 30000) // Every 30 seconds
    public void sendHeartbeat() {
        messagingTemplate.convertAndSend(
                "/topic/heartbeat", Map.of("timestamp", System.currentTimeMillis()));
    }

    // Helper methods

    private double calculateProgress(Order order) {
        if (order.getQuantity() == null || order.getQuantity() == 0) {
            return 0.0;
        }

        int completed =
                order.getQuantity()
                        - (order.getRemains() != null ? order.getRemains() : order.getQuantity());
        return (double) completed / order.getQuantity() * 100;
    }

    private LocalDateTime estimateCompletion(Order order, double currentProgress) {
        if (currentProgress == 0 || currentProgress >= 100) {
            return null;
        }

        // Simple estimation based on creation time and current progress
        LocalDateTime createdAt = order.getCreatedAt();
        LocalDateTime now = LocalDateTime.now();
        long minutesElapsed = java.time.Duration.between(createdAt, now).toMinutes();

        if (minutesElapsed == 0) {
            return null;
        }

        double minutesPerPercent = minutesElapsed / currentProgress;
        double remainingPercent = 100 - currentProgress;
        long estimatedMinutesRemaining = (long) (minutesPerPercent * remainingPercent);

        return now.plusMinutes(estimatedMinutesRemaining);
    }
}
