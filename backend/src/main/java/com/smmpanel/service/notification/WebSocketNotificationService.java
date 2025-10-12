package com.smmpanel.service.notification;

import com.smmpanel.entity.OrderStatus;
import com.smmpanel.websocket.OrderWebSocketController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Service to integrate WebSocket notifications with order processing Listens to order events and
 * sends real-time updates to clients
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketNotificationService {

    private final OrderWebSocketController webSocketController;

    /** Handle order status change events */
    @TransactionalEventListener
    public void handleOrderStatusChange(OrderStatusChangeEvent event) {
        try {
            webSocketController.broadcastOrderStatusChange(
                    event.getOrderId(), event.getNewStatus(), event.getMessage());

            // Send user notification
            webSocketController.sendUserNotification(
                    event.getUsername(),
                    "ORDER_UPDATE",
                    "Order Status Updated",
                    String.format(
                            "Your order #%d is now %s", event.getOrderId(), event.getNewStatus()));

        } catch (Exception e) {
            log.error("Failed to send WebSocket notification for order {}", event.getOrderId(), e);
        }
    }

    /** Handle order progress updates */
    @TransactionalEventListener
    public void handleOrderProgressUpdate(OrderProgressEvent event) {
        try {
            webSocketController.sendOrderProgress(
                    event.getOrderId(), event.getCompletedQuantity(), event.getTotalQuantity());
        } catch (Exception e) {
            log.error("Failed to send progress update for order {}", event.getOrderId(), e);
        }
    }

    /** Listen to Kafka order processing events */
    @KafkaListener(topics = "smm.order.status.updates", groupId = "websocket-notification-group")
    public void handleKafkaOrderUpdate(OrderUpdateKafkaMessage message) {
        try {
            log.debug("Received Kafka order update: {}", message);

            webSocketController.broadcastOrderStatusChange(
                    message.getOrderId(),
                    OrderStatus.valueOf(message.getStatus()),
                    message.getMessage());

        } catch (Exception e) {
            log.error("Failed to process Kafka order update", e);
        }
    }

    /** Send payment notification */
    public void sendPaymentNotification(String username, String type, String message) {
        webSocketController.sendUserNotification(username, "PAYMENT", type, message);
    }

    /** Send error notification */
    public void sendErrorNotification(String username, String error) {
        webSocketController.sendUserNotification(username, "ERROR", "Error Occurred", error);
    }

    /** Send order update to specific user */
    public void sendOrderUpdate(Long userId, String type, java.util.Map<String, Object> data) {
        try {
            // Convert userId to username - in production this would lookup from user service
            String username = "user_" + userId;
            String message =
                    data.get("message") != null ? data.get("message").toString() : "Order update";
            webSocketController.sendUserNotification(username, type, "Order Update", message);
        } catch (Exception e) {
            log.error("Failed to send order update to user {}", userId, e);
        }
    }

    // Event classes

    public static class OrderStatusChangeEvent {
        private final Long orderId;
        private final OrderStatus newStatus;
        private final String username;
        private final String message;

        public OrderStatusChangeEvent(
                Long orderId, OrderStatus newStatus, String username, String message) {
            this.orderId = orderId;
            this.newStatus = newStatus;
            this.username = username;
            this.message = message;
        }

        public Long getOrderId() {
            return orderId;
        }

        public OrderStatus getNewStatus() {
            return newStatus;
        }

        public String getUsername() {
            return username;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class OrderProgressEvent {
        private final Long orderId;
        private final Integer completedQuantity;
        private final Integer totalQuantity;

        public OrderProgressEvent(Long orderId, Integer completedQuantity, Integer totalQuantity) {
            this.orderId = orderId;
            this.completedQuantity = completedQuantity;
            this.totalQuantity = totalQuantity;
        }

        public Long getOrderId() {
            return orderId;
        }

        public Integer getCompletedQuantity() {
            return completedQuantity;
        }

        public Integer getTotalQuantity() {
            return totalQuantity;
        }
    }

    public static class OrderUpdateKafkaMessage {
        private Long orderId;
        private String status;
        private String message;

        public Long getOrderId() {
            return orderId;
        }

        public void setOrderId(Long orderId) {
            this.orderId = orderId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
