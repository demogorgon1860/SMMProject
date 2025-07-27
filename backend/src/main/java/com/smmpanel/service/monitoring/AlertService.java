package com.smmpanel.service.monitoring;

import com.smmpanel.service.NotificationService;
import com.smmpanel.service.SlackService;
import com.smmpanel.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {
    
    private final NotificationService notificationService;
    private final SlackService slackService;
    private final EmailService emailService;
    
    public void sendAlert(AlertLevel level, String message, Map<String, Object> details) {
        Alert alert = Alert.builder()
            .level(level)
            .message(message)
            .details(details)
            .timestamp(LocalDateTime.now())
            .build();
        
        // Send to appropriate channels based on alert level
        switch (level) {
            case INFO:
                slackService.sendToChannel("#monitoring", formatAlert(alert));
                log.info("Info Alert: {}", message);
                break;
                
            case WARNING:
                slackService.sendToChannel("#alerts", formatAlert(alert));
                emailService.sendToTeam("Warning Alert: " + message, formatAlert(alert));
                log.warn("Warning Alert: {}", message);
                break;
                
            case CRITICAL:
                slackService.sendToChannel("#alerts", formatAlert(alert));
                emailService.sendToTeam("CRITICAL ALERT: " + message, formatAlert(alert));
                notificationService.sendPushNotification("CRITICAL: " + message);
                log.error("CRITICAL ALERT: {}", message);
                break;
        }
        
        // Log all alerts for audit purposes
        logAlert(alert);
    }
    
    public void sendAlert(AlertLevel level, String message) {
        sendAlert(level, message, Map.of());
    }
    
    private String formatAlert(Alert alert) {
        StringBuilder sb = new StringBuilder();
        sb.append("*[").append(alert.getLevel()).append("]* ");
        sb.append(alert.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        sb.append("*Message:* ").append(alert.getMessage()).append("\n");
        
        if (!alert.getDetails().isEmpty()) {
            sb.append("*Details:*\n");
            alert.getDetails().forEach((key, value) -> 
                sb.append("  • ").append(key).append(": ").append(value).append("\n")
            );
        }
        
        return sb.toString();
    }
    
    private void logAlert(Alert alert) {
        // In a production environment, you might want to persist alerts to a database
        // For now, we'll just log them
        String logMessage = String.format(
            "[%s] %s - %s",
            alert.getLevel(),
            alert.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            alert.getMessage()
        );
        
        if (!alert.getDetails().isEmpty()) {
            logMessage += "\n" + alert.getDetails().entrySet().stream()
                .map(e -> "  " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        }
        
        log.info(logMessage);
    }
}
