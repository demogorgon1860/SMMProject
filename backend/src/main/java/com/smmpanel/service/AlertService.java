package com.smmpanel.service;

import com.smmpanel.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for managing system alerts and notifications
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final SlackService slackService;
    private final EmailService emailService;

    @Value("${app.alerts.admin-email:admin@smmpanel.com}")
    private String adminEmail;

    @Value("${app.alerts.enabled:true}")
    private boolean alertsEnabled;

    /**
     * Send an alert through all configured channels
     */
    public void sendAlert(String title, String message, String level) {
        if (!alertsEnabled) {
            log.debug("Alerts are disabled");
            return;
        }

        log.info("Sending alert - Level: {}, Title: {}, Message: {}", level, title, message);

        // Send to Slack
        try {
            slackService.sendAlert(title, message, level);
        } catch (Exception e) {
            log.error("Failed to send Slack alert: {}", e.getMessage());
        }

        // Send email for critical alerts
        if ("CRITICAL".equalsIgnoreCase(level) || "ERROR".equalsIgnoreCase(level)) {
            try {
                emailService.sendAlert(adminEmail, title, message, level);
            } catch (Exception e) {
                log.error("Failed to send email alert: {}", e.getMessage());
            }
        }
    }

    /**
     * Send an informational alert
     */
    public void sendInfo(String title, String message) {
        sendAlert(title, message, "INFO");
    }

    /**
     * Send a warning alert
     */
    public void sendWarning(String title, String message) {
        sendAlert(title, message, "WARNING");
    }

    /**
     * Send an error alert
     */
    public void sendError(String title, String message) {
        sendAlert(title, message, "ERROR");
    }

    /**
     * Send a critical alert
     */
    public void sendCritical(String title, String message) {
        sendAlert(title, message, "CRITICAL");
    }

    /**
     * Send a system notification
     */
    public void sendSystemNotification(String title, String message) {
        log.info("System notification - {}: {}", title, message);
        
        try {
            slackService.sendMessage(String.format("System: %s - %s", title, message));
        } catch (Exception e) {
            log.error("Failed to send system notification: {}", e.getMessage());
        }
    }
} 