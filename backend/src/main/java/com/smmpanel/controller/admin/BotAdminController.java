package com.smmpanel.controller.admin;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.client.InstagramBotClient.InstanceStatus;
import com.smmpanel.dto.admin.BotWebhookEventDto;
import com.smmpanel.service.admin.BotWebhookEventRecorder;
import com.smmpanel.service.admin.BotWebhookSseBroadcaster;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Admin operations for the live Instagram bot fleet. Surfaces real-time data for the {@code
 * /admin/bot} page — instance status, worker controls, queue snapshot, and a live webhook tail
 * (SSE).
 *
 * <p>All proxied calls into the bot use a 3-second timeout so the page stays responsive even when
 * an instance is down.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/bot")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class BotAdminController {

    private final InstagramBotClient botClient;
    private final BotWebhookEventRecorder webhookEventRecorder;
    private final BotWebhookSseBroadcaster sseBroadcaster;

    /**
     * Per-instance health + queue snapshot. If a bot is unreachable, its entry has {@code
     * online=false} and {@code lastError} populated — never a fake "up".
     */
    @GetMapping("/status")
    public ResponseEntity<List<InstanceStatus>> status() {
        return ResponseEntity.ok(botClient.getAllInstanceStatuses());
    }

    /** Start workers on a specific bot instance. {@code id} is "bot-01", "bot-02", etc. */
    @PostMapping("/instances/{id}/workers/start")
    public ResponseEntity<Map<String, Object>> workersStart(@PathVariable String id) {
        return proxy(id, instance -> botClient.setWorkers(instance, "start"));
    }

    /** Stop workers on a specific bot instance. */
    @PostMapping("/instances/{id}/workers/stop")
    public ResponseEntity<Map<String, Object>> workersStop(@PathVariable String id) {
        return proxy(id, instance -> botClient.setWorkers(instance, "stop"));
    }

    /**
     * Begin graceful drain on a specific bot instance — stop pulling new orders, let in-flight
     * workers finish. Reversible via {@link #workersResume(String)}.
     */
    @PostMapping("/instances/{id}/workers/drain")
    public ResponseEntity<Map<String, Object>> workersDrain(@PathVariable String id) {
        return proxy(id, instance -> botClient.setDrain(instance, "start"));
    }

    /** Stop draining on a specific bot instance — order processor resumes pulling new work. */
    @PostMapping("/instances/{id}/workers/resume")
    public ResponseEntity<Map<String, Object>> workersResume(@PathVariable String id) {
        return proxy(id, instance -> botClient.setDrain(instance, "stop"));
    }

    /** Re-read .env and environment variables on the target bot. */
    @PostMapping("/instances/{id}/reload")
    public ResponseEntity<Map<String, Object>> reload(@PathVariable String id) {
        return proxy(id, botClient::reload);
    }

    /**
     * List orders from a specific bot instance. Optional filter by {@code status} (pending,
     * processing, scouting, completed, failed, cancelled, partial). {@code limit} caps the returned
     * list — newest orders are returned (best-effort by createdAt; bot order is the source of truth
     * and does not guarantee sort order).
     */
    @GetMapping("/instances/{id}/queue")
    public ResponseEntity<?> queue(
            @PathVariable String id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        String instance = resolveInstance(id);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> orders = botClient.listOrdersFromInstance(instance);
        java.util.stream.Stream<Map<String, Object>> stream = orders.stream();
        if (status != null && !status.isBlank()) {
            String want = status.toLowerCase();
            stream = stream.filter(o -> want.equalsIgnoreCase((String) o.get("status")));
        }
        List<Map<String, Object>> filtered =
                stream.limit(Math.max(1, Math.min(limit, 500))).toList();
        return ResponseEntity.ok(filtered);
    }

    /**
     * Last N webhook/result events received from any bot, newest first. Backed by a Redis LIST
     * capped at 200 entries — both HTTP webhook and RabbitMQ result paths feed into it.
     */
    @GetMapping("/webhooks/recent")
    public ResponseEntity<List<BotWebhookEventDto>> recentWebhooks(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(webhookEventRecorder.getRecent(limit));
    }

    /**
     * Server-Sent Events stream of webhook/result events as they arrive. Connection is cleaned up
     * automatically on browser close (timeout, error, or completion).
     */
    @GetMapping(path = "/webhooks/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamWebhooks() {
        return sseBroadcaster.subscribe();
    }

    // ---- helpers ----

    /** Look up the bot URL by id ("bot-01" → first instance, etc). Returns null if not found. */
    private String resolveInstance(String id) {
        List<String> instances = botClient.getBotInstances();
        if (id == null || !id.startsWith("bot-")) {
            return null;
        }
        try {
            int idx = Integer.parseInt(id.substring("bot-".length())) - 1;
            if (idx < 0 || idx >= instances.size()) return null;
            return instances.get(idx);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ResponseEntity<Map<String, Object>> proxy(
            String id, java.util.function.Function<String, Map<String, Object>> fn) {
        String instance = resolveInstance(id);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = fn.apply(instance);
        return ResponseEntity.ok(result);
    }
}
