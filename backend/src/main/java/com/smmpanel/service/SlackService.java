package com.smmpanel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** Service for sending notifications to Slack */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackService {

    private final RestTemplate restTemplate;

    @Value("${app.slack.webhook-url:}")
    private String webhookUrl;

    @Value("${app.slack.enabled:false}")
    private boolean enabled;

    /** Send a simple message to Slack */
    public void sendMessage(String message) {
        if (!enabled || webhookUrl.isEmpty()) {
            log.debug("Slack notifications disabled or webhook URL not configured");
            return;
        }

        try {
            // Simple Slack webhook payload
            String payload = String.format("{\"text\": \"%s\"}", message.replace("\"", "\\\""));

            restTemplate.postForEntity(webhookUrl, payload, String.class);
            log.debug("Slack message sent successfully: {}", message);
        } catch (Exception e) {
            log.error("Failed to send Slack message: {}", e.getMessage());
        }
    }

    /** Send an alert message to Slack */
    public void sendAlert(String title, String message, String level) {
        if (!enabled || webhookUrl.isEmpty()) {
            return;
        }

        try {
            String color = getColorForLevel(level);
            String payload =
                    String.format(
                            "{ \"attachments\": [{ \"color\": \"%s\", \"title\": \"%s\", \"text\":"
                                    + " \"%s\" }] }",
                            color, title.replace("\"", "\\\""), message.replace("\"", "\\\""));

            restTemplate.postForEntity(webhookUrl, payload, String.class);
            log.debug("Slack alert sent: {} - {}", title, level);
        } catch (Exception e) {
            log.error("Failed to send Slack alert: {}", e.getMessage());
        }
    }

    private String getColorForLevel(String level) {
        return switch (level.toUpperCase()) {
            case "ERROR", "CRITICAL" -> "#ff0000";
            case "WARNING", "WARN" -> "#ffaa00";
            case "INFO" -> "#00aaff";
            default -> "#808080";
        };
    }

    public void sendToChannel(String channel, String message) {
        log.info("Sending to Slack channel {}: {}", channel, message);
        // Implementation
    }
}
