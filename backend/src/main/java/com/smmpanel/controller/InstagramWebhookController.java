package com.smmpanel.controller;

import com.smmpanel.dto.instagram.InstagramHealthResponse;
import com.smmpanel.dto.instagram.InstagramWebhookCallback;
import com.smmpanel.service.integration.InstagramService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Controller for handling Instagram bot webhooks and admin operations. */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InstagramWebhookController {

    private final InstagramService instagramService;

    /** Webhook endpoint for Instagram bot callbacks. Called when an order completes or fails. */
    @PostMapping("/webhook/instagram")
    public ResponseEntity<Map<String, String>> handleInstagramWebhook(
            @RequestBody InstagramWebhookCallback callback) {

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
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
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
