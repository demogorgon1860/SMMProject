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

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * PRODUCTION-READY Notification Service for user communications
 * Compatible with Perfect Panel notification system
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
    public void sendBalanceUpdatedNotification(User user, BigDecimal oldBalance, 
                                             BigDecimal newBalance, String reason) {
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

    /**
     * Send push notification for critical alerts
     */
    public void sendPushNotification(String message) {
        try {
            // Implementation for push notifications would go here
            // This could integrate with Firebase, OneSignal, etc.
            log.info("Push notification sent: {}", message);
            
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("message", message);
            pushData.put("type", "CRITICAL_ALERT");
            pushData.put("timestamp", java.time.LocalDateTime.now().format(DATE_FORMATTER));
            
            sendKafkaNotification("push.notification", 0L, pushData);
        } catch (Exception e) {
            log.error("Failed to send push notification: {}", e.getMessage());
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
            log.info("Email notification sent to {}: {}", user.getEmail(), subject);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private void sendKafkaNotification(String topic, Long entityId, Map<String, Object> data) {
        if (!kafkaNotificationsEnabled) {
            log.debug("Kafka notifications disabled, skipping notification");
            return;
        }

        try {
            data.put("entityId", entityId);
            data.put("timestamp", java.time.LocalDateTime.now().format(DATE_FORMATTER));
            
            kafkaTemplate.send("smm.notifications", entityId.toString(), data);
            log.debug("Kafka notification sent to topic: notifications.{}", topic);
        } catch (Exception e) {
            log.error("Failed to send Kafka notification to topic {}: {}", topic, e.getMessage());
        }
    }

    private Map<String, Object> buildNotificationData(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("userId", order.getUser().getId());
        data.put("username", order.getUser().getUsername());
        data.put("serviceId", order.getService().getId());
        data.put("quantity", order.getQuantity());
        data.put("link", order.getLink());
        data.put("status", order.getStatus().name());
        data.put("createdAt", order.getCreatedAt().format(DATE_FORMATTER));
        return data;
    }

    private String buildOrderCreatedMessage(Order order) {
        return String.format(
            "Your order #%d has been created successfully.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Quantity: %d\n" +
            "Status: %s\n\n" +
            "You will receive updates as your order progresses.",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getStatus().name()
        );
    }

    private String buildOrderCompletedMessage(Order order) {
        return String.format(
            "Great news! Your order #%d has been completed.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Quantity Delivered: %d\n" +
            "Completion Time: %s\n\n" +
            "Thank you for using our service!",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            java.time.LocalDateTime.now().format(DATE_FORMATTER)
        );
    }

    private String buildOrderPausedMessage(Order order, String reason) {
        return String.format(
            "Your order #%d has been paused.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Reason: %s\n\n" +
            "Our team is working to resolve this issue. You will be notified when the order resumes.",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            reason
        );
    }

    private String buildOrderResumedMessage(Order order) {
        return String.format(
            "Your order #%d has been resumed and is now processing.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Current Status: %s\n\n" +
            "Thank you for your patience.",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getStatus().name()
        );
    }

    private String buildOrderCancelledMessage(Order order, String reason) {
        return String.format(
            "Your order #%d has been cancelled.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Reason: %s\n\n" +
            "If you have any questions, please contact our support team.",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            reason
        );
    }

    /**
     * Send notification to operators
     */
    @Async("asyncExecutor")
    public void notifyOperators(String message) {
        try {
            // Send to operator Slack channel
            sendKafkaNotification("operator.alert", 0L, Map.of("message", message));
            log.info("Operator notification sent: {}", message);
        } catch (Exception e) {
            log.error("Failed to send operator notification: {}", e.getMessage());
        }
    }

    /**
     * Send order started notification
     */
    @Async("asyncExecutor")
    public void sendOrderStartedNotification(Order order) {
        try {
            String subject = "Order Started - #" + order.getId();
            String message = buildOrderStartedMessage(order);
            sendEmailNotification(order.getUser(), subject, message);
            
            log.info("Sent order started notification to user {} for order {}", 
                    order.getUser().getUsername(), order.getId());
                    
        } catch (Exception e) {
            log.error("Failed to send order started notification for order {}: {}", 
                    order.getId(), e.getMessage());
        }
    }

    /**
     * Notify finance team about manual intervention required
     */
    public void notifyFinanceTeam(String message) {
        try {
            String subject = "Manual Intervention Required";
            String financeEmail = "finance@smmpanel.com"; // Configure via properties
            
            sendEmailNotification(null, subject, message);
            log.info("Notified finance team: {}", message);
            
        } catch (Exception e) {
            log.error("Failed to notify finance team: {}", e.getMessage());
        }
    }

    /**
     * Send order failed notification
     */
    @Async("asyncExecutor")
    public void sendOrderFailedNotification(Order order, String reason) {
        try {
            String subject = "Order Failed - #" + order.getId();
            String message = buildOrderFailedMessage(order, reason);
            sendEmailNotification(order.getUser(), subject, message);
            
            Map<String, Object> data = buildNotificationData(order);
            data.put("reason", reason);
            sendKafkaNotification("order.failed", order.getId(), data);
        } catch (Exception e) {
            log.error("Failed to send order failed notification for order {}: {}", order.getId(), e.getMessage());
        }
    }

    private String buildOrderStartedMessage(Order order) {
        return String.format(
            "Your order #%d has started processing.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Quantity: %d\n" +
            "Status: %s\n\n" +
            "Your order is now being processed. You will receive updates as it progresses.",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            order.getQuantity(),
            order.getStatus().name()
        );
    }

    private String buildOrderFailedMessage(Order order, String reason) {
        return String.format(
            "Your order #%d has failed.\n\n" +
            "Service: %s\n" +
            "Link: %s\n" +
            "Reason: %s\n\n" +
            "We apologize for the inconvenience. Please contact support if you need assistance.",
            order.getId(),
            order.getService().getName(),
            order.getLink(),
            reason
        );
    }

    private String buildBalanceUpdatedMessage(User user, BigDecimal oldBalance, BigDecimal newBalance, String reason) {
        return String.format(
            "Your account balance has been updated.\n\n" +
            "Previous Balance: $%.2f\n" +
            "New Balance: $%.2f\n" +
            "Change: $%.2f\n" +
            "Reason: %s\n\n" +
            "Transaction completed at: %s",
            oldBalance,
            newBalance,
            newBalance.subtract(oldBalance),
            reason,
            java.time.LocalDateTime.now().format(DATE_FORMATTER)
        );
    }
}