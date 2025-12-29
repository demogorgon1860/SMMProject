package com.smmpanel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.CryptomusClient;
import com.smmpanel.dto.cryptomus.CryptomusWebhook;
import com.smmpanel.service.integration.CryptomusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class WebhookController {

    private final CryptomusService cryptomusService;
    private final CryptomusClient cryptomusClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/cryptomus/callback")
    public ResponseEntity<Map<String, String>> handleCryptomusWebhook(
            @RequestBody String rawBody, HttpServletRequest request) {

        try {
            // Parse the raw JSON to extract webhook data
            CryptomusWebhook webhook = objectMapper.readValue(rawBody, CryptomusWebhook.class);

            log.info("Received Cryptomus webhook for order: {}", webhook.getOrderId());
            log.debug("Raw webhook body: {}", rawBody);

            // SECURITY: IP Whitelist - Only accept webhooks from Cryptomus official IP
            String clientIp = getClientIpAddress(request);
            String cryptomusOfficialIp = "91.227.144.54";

            if (!cryptomusOfficialIp.equals(clientIp)) {
                log.warn(
                        "Rejected webhook from unauthorized IP: {} (expected: {}). Order: {}",
                        clientIp,
                        cryptomusOfficialIp,
                        webhook.getOrderId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("status", "error", "message", "Unauthorized IP address"));
            }

            log.info("Webhook IP verified: {} for order: {}", clientIp, webhook.getOrderId());

            // Verify webhook signature for security using RAW JSON
            // The sign field is now part of the webhook body (per Cryptomus docs)
            if (webhook.getSign() != null) {
                boolean isValid =
                        cryptomusService.validateWebhookSignature(rawBody, webhook.getSign());

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
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("status", "error", "message", "Missing signature"));
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

    /**
     * Extract client IP address from request, handling load balancer headers
     *
     * @param request HttpServletRequest
     * @return Client IP address
     */
    private String getClientIpAddress(HttpServletRequest request) {
        // Check X-Forwarded-For header (set by load balancers/proxies)
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2"
            // The first IP is the original client
            String[] ips = xForwardedFor.split(",");
            return ips[0].trim();
        }

        // Check X-Real-IP header (alternative header used by some proxies)
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp.trim();
        }

        // Fallback to remote address
        return request.getRemoteAddr();
    }
}
