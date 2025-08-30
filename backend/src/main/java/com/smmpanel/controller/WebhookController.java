package com.smmpanel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.CryptomusClient;
import com.smmpanel.dto.cryptomus.CryptomusWebhook;
import com.smmpanel.service.CryptomusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v2/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final CryptomusService cryptomusService;
    private final CryptomusClient cryptomusClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/cryptomus")
    public ResponseEntity<Map<String, String>> handleCryptomusWebhook(
            @RequestBody CryptomusWebhook webhook,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            HttpServletRequest request) {

        try {
            log.info("Received Cryptomus webhook for order: {}", webhook.getOrderId());

            // Verify webhook signature for security
            if (signature != null) {
                String webhookData = objectMapper.writeValueAsString(webhook);
                boolean isValid = cryptomusClient.verifyWebhook(signature, webhookData);

                if (!isValid) {
                    log.warn("Invalid webhook signature for order: {}", webhook.getOrderId());
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("status", "error", "message", "Invalid signature"));
                }

                log.debug(
                        "Webhook signature verified successfully for order: {}",
                        webhook.getOrderId());
            } else {
                log.warn(
                        "No signature provided in webhook request for order: {}",
                        webhook.getOrderId());
                // In production, you should reject unsigned webhooks
                // For now, log warning but continue processing
            }

            // Process the webhook
            cryptomusService.processWebhook(webhook);

            // Return success response expected by Cryptomus
            return ResponseEntity.ok(Map.of("status", "success"));

        } catch (Exception e) {
            log.error("Failed to process Cryptomus webhook: {}", e.getMessage(), e);

            // Return error response
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> testWebhook(
            @RequestBody Map<String, Object> payload) {
        log.info("Test webhook received: {}", payload);
        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "received",
                        "timestamp",
                        String.valueOf(System.currentTimeMillis())));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "webhooks"));
    }
}
