package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * PRODUCTION-READY Notification Service for user communications
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender mailSender;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${app.mail.from:noreply@smmpanel.com}")
    private String fromEmail;

    @Value("${app.mail.enabled:true}")
    private boolean emailEnabled;

    @Value("${app.notifications.kafka.enabled:true}")
    private boolean kafkaNotificationsEnabled;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Order notification methods
    @Async("asyncExecutor")
    public void sendOrderCreatedNotification(Order order) {
        try {
            String subject = "Order Created - #" + order.getId();
            String message = buildOrderCreatedMessage(order);
            sendEmailNotification(order.getUser(), subject, message);
            sendKafkaNotification("order.created", order.getId(), buildNotificationData(order));
        } catch (Exception e) {
            log.error("Failed to send order created notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendOrderStartedNotification(Order order) {
        try {
            String subject = "Order Started - #" + order.getId();
            String message = buildOrderStartedMessage(order);
            sendEmailNotification(order.getUser(), subject, message);
            sendKafkaNotification("order.started", order.getId(), buildNotificationData(order));
        } catch (Exception e) {
            log.error("Failed to send order started notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendOrderCompletedNotification(Order order) {
        try {
            String subject = "Order Completed - #" + order.getId();
            String message = buildOrderCompletedMessage(order);
            sendEmailNotification(order.getUser(), subject, message);
            sendKafkaNotification("order.completed", order.getId(), buildNotificationData(order));
        } catch (Exception e) {
            log.error("Failed to send order completed notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendOrderFailedNotification(Order order, String errorMessage) {
        try {
            String subject = "Order Failed - #" + order.getId();
            String message = buildOrderFailedMessage(order, errorMessage);
            sendEmailNotification(order.getUser(), subject, message);
            
            Map<String, Object> data = buildNotificationData(order);
            data.put("errorMessage", errorMessage);
            sendKafkaNotification("order.failed", order.getId(), data);
        } catch (Exception e) {
            log.error("Failed to send order failed notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendOrderPausedNotification(Order order, String reason) {
        try {
            String subject = "Order Paused - #" + order.getId();
            String message = buildOrderPausedMessage(order, reason);
            sendEmailNotification(order.getUser(), subject, message);
            
            Map<String, Object> data = buildNotificationData(order);
            data.put("reason", reason);
            sendKafkaNotification("order.paused", order.getId(), data);
        } catch (Exception e) {
            log.error("Failed to send order paused notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendOrderResumedNotification(Order order) {
        try {
            String subject = "Order Resumed - #" + order.getId();
            String message = buildOrderResumedMessage(order);
            sendEmailNotification(order.getUser(), subject, message);
            sendKafkaNotification("order.resumed", order.getId(), buildNotificationData(order));
        } catch (Exception e) {
            log.error("Failed to send order resumed notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendOrderCancelledNotification(Order order, String reason) {
        try {
            String subject = "Order Cancelled - #" + order.getId();
            String message = buildOrderCancelledMessage(order, reason);
            sendEmailNotification(order.getUser(), subject, message);
            
            Map<String, Object> data = buildNotificationData(order);
            data.put("reason", reason);
            sendKafkaNotification("order.cancelled", order.getId(), data);
        } catch (Exception e) {
            log.error("Failed to send order cancelled notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    @Async("asyncExecutor")
    public void sendBalanceUpdatedNotification(User user, java.math.BigDecimal oldBalance, 
                                             java.math.BigDecimal newBalance, String reason) {
        try {
            String subject = "Balance Updated";
            String message = buildBalanceUpdatedMessage(user, oldBalance, newBalance, reason);
            sendEmailNotification(user, subject, message);
            
            Map<String, Object> data = new HashMap<>();
            data.put("userId", user.getId());
            data.put("username", user.getUsername());
            data.put("oldBalance", oldBalance);
            data.put("newBalance", newBalance);
            data.put("reason", reason);
            data.put("timestamp", java.time.LocalDateTime.now().format(DATE_FORMATTER));
            
            sendKafkaNotification("balance.updated", user.getId(), data);
        } catch (Exception e) {
            log.error("Failed to send balance updated notification for user {}: {}", 
                    user.getUsername(), e.getMessage());
        }
    }

    // Private helper methods
    private void sendEmailNotification(User user, String subject, String message) {
        if (!emailEnabled || user.getEmail() == null || user.getEmail().isEmpty()) {
            log.debug("Email notification skipped for user {} - email disabled or no email", user.getUsername());
            return;
        }

        try {
            SimpleMailMessage mailMessage = new SimpleMailMessage();
            mailMessage.setFrom(fromEmail);
            mailMessage.setTo(user.getEmail());
            mailMessage.setSubject(subject);
            mailMessage.setText(message);
            mailSender.send(mailMessage);
            log.debug("Email sent to {}: {}", user.getEmail(), subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private void sendKafkaNotification(String eventType, Long entityId, Map<String, Object> data) {
        if (!kafkaNotificationsEnabled) {
            log.debug("Kafka notification skipped - disabled");
            return;
        }

        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("eventType", eventType);
            notification.put("entityId", entityId);
            notification.put("data", data);
            notification.put("timestamp", java.time.LocalDateTime.now().format(DATE_FORMATTER));
            kafkaTemplate.send("notifications", notification);
            log.debug("Kafka notification sent: {} for entity {}", eventType, entityId);
        } catch (Exception e) {
            log.error("Failed to send Kafka notification: {}", e.getMessage());
        }
    }

    // ==================== MESSAGE BUILDERS ====================

    private Map<String, Object> buildNotificationData(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("userId", order.getUser().getId());
        data.put("username", order.getUser().getUsername());
        data.put("serviceId", order.getService().getId());
        data.put("serviceName", order.getService().getName());
        data.put("link", order.getLink());
        data.put("quantity", order.getQuantity());
        data.put("remains", order.getRemains());
        data.put("status", order.getStatus().toString());
        data.put("charge", order.getCharge() != null ? order.getCharge().toString() : "0.00");
        data.put("createdAt", order.getCreatedAt().format(DATE_FORMATTER));
        return data;
    }

    private String buildOrderCreatedMessage(Order order) {
        return String.format(
            "Dear %s,%n%n" +
            "Your order has been created successfully!%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Charge: $%s%n" +
            "- Status: %s%n" +
            "- Created: %s%n%n" +
            "Your order is now being processed. You will receive updates as the status changes.%n%n" +
            "Thank you for using our service!%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getCharge() != null ? order.getCharge().toString() : "0.00",
            order.getStatus(),
            order.getCreatedAt().format(DATE_FORMATTER)
        );
    }

    private String buildOrderStartedMessage(Order order) {
        return String.format(
            "Dear %s,%n%n" +
            "Your order has started processing!%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Start Count: %d%n" +
            "- Status: %s%n%n" +
            "Delivery is now in progress. You can track the progress in your dashboard.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getStartCount() != null ? order.getStartCount() : 0,
            order.getStatus()
        );
    }

    private String buildOrderCompletedMessage(Order order) {
        return String.format(
            "Dear %s,%n%n" +
            "Congratulations! Your order has been completed successfully!%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Start Count: %d%n" +
            "- Delivered: %d%n" +
            "- Status: %s%n%n" +
            "Thank you for using our service! We hope you're satisfied with the results.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getStartCount() != null ? order.getStartCount() : 0,
            order.getQuantity() - order.getRemains(),
            order.getStatus()
        );
    }

    private String buildOrderFailedMessage(Order order, String errorMessage) {
        return String.format(
            "Dear %s,%n%n" +
            "We're sorry to inform you that your order has failed to process.%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Status: %s%n" +
            "- Error: %s%n%n" +
            "If you were charged for this order, the amount will be refunded to your account balance.%n" +
            "Please contact support if you need assistance or have any questions.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getStatus(),
            errorMessage
        );
    }

    private String buildOrderPausedMessage(Order order, String reason) {
        return String.format(
            "Dear %s,%n%n" +
            "Your order has been paused.%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Delivered: %d%n" +
            "- Remaining: %d%n" +
            "- Status: %s%n" +
            "- Reason: %s%n%n" +
            "The order will resume automatically once the issue is resolved.%n" +
            "Please contact support if you need assistance.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getQuantity() - order.getRemains(),
            order.getRemains(),
            order.getStatus(),
            reason
        );
    }

    private String buildOrderResumedMessage(Order order) {
        return String.format(
            "Dear %s,%n%n" +
            "Your order has been resumed and processing will continue.%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Delivered: %d%n" +
            "- Remaining: %d%n" +
            "- Status: %s%n%n" +
            "Delivery will continue from where it left off.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getQuantity() - order.getRemains(),
            order.getRemains(),
            order.getStatus()
        );
    }

    private String buildOrderCancelledMessage(Order order, String reason) {
        return String.format(
            "Dear %s,%n%n" +
            "Your order has been cancelled.%n%n" +
            "Order Details:%n" +
            "- Order ID: #%d%n" +
            "- Service: %s%n" +
            "- Link: %s%n" +
            "- Quantity: %d%n" +
            "- Delivered: %d%n" +
            "- Status: %s%n" +
            "- Reason: %s%n%n" +
            "If applicable, any refund will be processed to your account balance.%n" +
            "Please contact support if you have any questions.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            order.getUser().getUsername(),
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getQuantity() - order.getRemains(),
            order.getStatus(),
            reason
        );
    }

    private String buildBalanceUpdatedMessage(User user, java.math.BigDecimal oldBalance, 
                                           java.math.BigDecimal newBalance, String reason) {
        java.math.BigDecimal change = newBalance.subtract(oldBalance);
        String changeType = change.compareTo(java.math.BigDecimal.ZERO) >= 0 ? "added to" : "deducted from";
        
        return String.format(
            "Dear %s,%n%n" +
            "Your account balance has been updated.%n%n" +
            "Balance Details:%n" +
            "- Previous Balance: $%s%n" +
            "- New Balance: $%s%n" +
            "- Amount %s: $%s%n" +
            "- Reason: %s%n" +
            "- Date: %s%n%n" +
            "You can view your complete balance history in your account dashboard.%n%n" +
            "Best regards,%n" +
            "SMM Panel Team",
            user.getUsername(),
            oldBalance.toString(),
            newBalance.toString(),
            changeType,
            change.abs().toString(),
            reason,
            java.time.LocalDateTime.now().format(DATE_FORMATTER)
        );
    }
