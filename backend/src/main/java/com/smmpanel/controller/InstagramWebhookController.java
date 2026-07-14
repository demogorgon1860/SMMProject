package com.smmpanel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.instagram.InstagramHealthResponse;
import com.smmpanel.dto.instagram.InstagramWebhookCallback;
import com.smmpanel.service.integration.InstagramService;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/** Controller for handling Instagram bot webhooks and admin operations. */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InstagramWebhookController {

    private final InstagramService instagramService;
    private final ObjectMapper objectMapper;

    // The bot HMAC-signs every HTTP webhook: X-Webhook-Signature = hex(HMAC-SHA256(body)) with the
    // SAME shared key the panel authenticates to the bot with (EXTERNAL_API_KEY on the bot ==
    // app.instagram.bot.api-key here). We verify with that key — no separate secret to distribute,
    // and the secret never travels on the wire.
    @Value("${app.instagram.bot.api-key:}")
    private String botApiKey;

    // Emergency off-switch (no redeploy needed) in case of a key mismatch after rotation.
    @Value("${app.instagram.webhook.verify-signature:true}")
    private boolean verifySignature;

    @PostConstruct
    void warnIfUnverified() {
        if (!verifySignature) {
            log.warn(
                    "⚠️  Instagram webhook signature verification is DISABLED"
                            + " (app.instagram.webhook.verify-signature=false) — callbacks are"
                            + " unauthenticated.");
        } else if (!StringUtils.hasText(botApiKey)) {
            log.warn(
                    "⚠️  app.instagram.bot.api-key is empty — Instagram webhook signatures cannot"
                            + " be verified, so POST /api/webhook/instagram accepts unauthenticated"
                            + " callbacks.");
        }
    }

    /**
     * Webhook endpoint for Instagram bot callbacks. Takes the RAW body so the HMAC is verified over
     * the exact bytes the bot signed (re-serializing a parsed object would change them).
     */
    @PostMapping("/webhook/instagram")
    public ResponseEntity<Map<String, String>> handleInstagramWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature) {

        // Verify the bot's HMAC signature over the exact received bytes (fail-closed) unless
        // verification is disabled or no key is configured.
        if (verifySignature && StringUtils.hasText(botApiKey)) {
            String expected = hmacSha256Hex(rawBody, botApiKey);
            if (expected == null
                    || !StringUtils.hasText(signature)
                    || !MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            signature.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Rejected Instagram webhook: missing/invalid X-Webhook-Signature");
                return ResponseEntity.status(401).body(Map.of("status", "unauthorized"));
            }
        }

        final InstagramWebhookCallback callback;
        try {
            callback = objectMapper.readValue(rawBody, InstagramWebhookCallback.class);
        } catch (Exception e) {
            log.warn("Rejected Instagram webhook: unparseable body ({} bytes)", rawBody.length);
            return ResponseEntity.badRequest().body(Map.of("status", "bad_request"));
        }

        log.info(
                "Received Instagram webhook: event={}, external_id={}, status={}",
                callback.getEvent(),
                callback.getExternalId(),
                callback.getStatus());

        try {
            instagramService.processWebhookCallback(callback);
            return ResponseEntity.ok(Map.of("status", "received"));
        } catch (Exception e) {
            log.error("Error processing Instagram webhook: {}", e.getMessage(), e);
            // Return 500 (not 200) so the bot retries a genuinely failed delivery, and do NOT
            // leak the exception message to the caller.
            return ResponseEntity.status(500).body(Map.of("status", "error"));
        }
    }

    /** HMAC-SHA256(body) as lowercase hex — matches the bot's utils.WebhookService.signPayload. */
    private String hmacSha256Hex(byte[] body, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("Failed to compute webhook HMAC: {}", e.getMessage());
            return null;
        }
    }

    /** Get Instagram bot health status. */
    @GetMapping("/v1/instagram/health")
    public ResponseEntity<InstagramHealthResponse> getBotHealth() {
        InstagramHealthResponse health = instagramService.checkBotHealth();
        return ResponseEntity.ok(health);
    }

    /** Check if Instagram bot is ready. */
    @GetMapping("/v1/instagram/ready")
    public ResponseEntity<Map<String, Object>> isBotReady() {
        boolean ready = instagramService.isBotReady();
        return ResponseEntity.ok(Map.of("ready", ready, "status", ready ? "UP" : "DOWN"));
    }

    /** Get Instagram bot queue statistics. */
    @GetMapping("/v1/instagram/stats")
    public ResponseEntity<Map<String, Object>> getBotStats() {
        Map<String, Object> stats = instagramService.getBotQueueStats();
        return ResponseEntity.ok(stats);
    }

    /** Control Instagram bot workers (admin only). */
    @PostMapping("/v2/admin/instagram/workers")
    public ResponseEntity<Map<String, Object>> controlWorkers(
            @RequestBody Map<String, String> request) {

        String action = request.get("action");
        if (action == null || (!action.equals("start") && !action.equals("stop"))) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "error",
                                    "Invalid action. Use 'start' or 'stop'"));
        }

        boolean success = instagramService.controlBotWorkers(action);
        return ResponseEntity.ok(
                Map.of(
                        "success", success,
                        "action", action));
    }

    /** Cancel an Instagram order (admin only). */
    @PostMapping("/v2/admin/instagram/orders/{orderId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable Long orderId) {
        boolean cancelled = instagramService.cancelOrder(orderId);
        return ResponseEntity.ok(
                Map.of(
                        "success", cancelled,
                        "orderId", orderId));
    }
}
