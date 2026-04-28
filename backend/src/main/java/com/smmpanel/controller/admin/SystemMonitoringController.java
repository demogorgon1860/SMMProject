package com.smmpanel.controller.admin;

import com.smmpanel.dto.admin.CacheStatsDto;
import com.smmpanel.dto.admin.QueueStatsDto;
import com.smmpanel.dto.admin.SystemErrorGroupDto;
import com.smmpanel.dto.admin.SystemLogEntryDto;
import com.smmpanel.service.admin.CacheStatsService;
import com.smmpanel.service.admin.QueueAdminService;
import com.smmpanel.service.admin.SystemLogService;
import com.smmpanel.service.admin.SystemLogSseBroadcaster;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Backend for the {@code /admin/system} page. Four read paths (Logs, Errors, Queues, Cache) and two
 * destructive admin actions (Purge DLQ, Flush cache).
 *
 * <p>All endpoints under {@code /api/v2/admin/system/...}. Source-unreachable failures (Redis or
 * RabbitMQ down) surface as HTTP 503 so the UI can render an honest "Source unreachable" empty
 * state rather than a fake fallback.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/system")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class SystemMonitoringController {

    /** Phrase the Cache flush form must POST in order for the action to execute. */
    private static final String CACHE_FLUSH_CONFIRMATION = "FLUSH";

    private final SystemLogService systemLogService;
    private final SystemLogSseBroadcaster systemLogSseBroadcaster;
    private final QueueAdminService queueAdminService;
    private final CacheStatsService cacheStatsService;

    // ---------------- Logs ----------------

    /**
     * Most recent log entries, newest first. Filters apply post-fetch since Redis LIST has no
     * server-side filtering. Returns 503 when Redis is unreachable.
     */
    @GetMapping("/logs")
    public ResponseEntity<List<SystemLogEntryDto>> logs(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "200") int limit) {
        try {
            return ResponseEntity.ok(systemLogService.getRecent(level, search, source, limit));
        } catch (DataAccessException e) {
            log.warn("Logs endpoint: Redis unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * Live SSE tail. Subscribes to the Redis pub/sub channel that {@link
     * com.smmpanel.monitoring.LogbackRedisAppender} publishes on. Auto-cleans on disconnect.
     */
    @GetMapping(path = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter logsStream() {
        return systemLogSseBroadcaster.subscribe();
    }

    // ---------------- Errors ----------------

    /**
     * ERROR-level entries from the last {@code sinceHours} hours, grouped by normalized message
     * hash. Each group includes up to 5 sample entries with their original stack traces so the UI
     * can expand inline.
     */
    @GetMapping("/errors")
    public ResponseEntity<List<SystemErrorGroupDto>> errors(
            @RequestParam(name = "since", defaultValue = "24") int sinceHours) {
        try {
            return ResponseEntity.ok(systemLogService.getErrorsGrouped(sinceHours));
        } catch (DataAccessException e) {
            log.warn("Errors endpoint: Redis unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * Just the count of distinct ERROR entries within the window — used by the tab badge so the UI
     * can render the badge without fetching the full grouped list.
     */
    @GetMapping("/errors/count")
    public ResponseEntity<Map<String, Long>> errorsCount(
            @RequestParam(name = "since", defaultValue = "24") int sinceHours) {
        try {
            long count = systemLogService.countErrors(sinceHours);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (DataAccessException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    // ---------------- Queues ----------------

    /**
     * All queues on the configured vhost. 503 when both management API and AMQP are unreachable.
     */
    @GetMapping("/queues")
    public ResponseEntity<List<QueueStatsDto>> queues() {
        try {
            return ResponseEntity.ok(queueAdminService.listQueues());
        } catch (Exception e) {
            log.warn("Queues endpoint: source unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * Purge a DLQ. {@link QueueAdminService#purgeQueue(String)} guards against purging non-DLQ
     * queues. ADMIN only — purging is destructive even when scoped to dead-letter material.
     */
    @PostMapping("/queues/{name}/purge")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> purgeQueue(
            @PathVariable("name") String name, HttpServletRequest req) {
        if (!QueueAdminService.isLikelyDlq(name)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Refusing to purge non-DLQ queue", "queue", name));
        }
        try {
            long purged = queueAdminService.purgeQueue(name);
            log.warn(
                    "ADMIN ACTION: queue purge — actor={} queue={} purged={} ip={}",
                    currentActor(),
                    name,
                    purged,
                    clientIp(req));
            return ResponseEntity.ok(Map.of("queue", name, "purged", purged));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(Map.of("error", iae.getMessage()));
        } catch (Exception e) {
            log.warn("Queue purge failed for {}: {}", name, e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Purge failed: " + e.getMessage()));
        }
    }

    // ---------------- Cache ----------------

    /** Redis INFO snapshot. 503 when Redis is unreachable. */
    @GetMapping("/cache")
    public ResponseEntity<CacheStatsDto> cache() {
        try {
            return ResponseEntity.ok(cacheStatsService.getStats());
        } catch (DataAccessException e) {
            log.warn("Cache endpoint: Redis unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * Flush the active Redis database. ADMIN only. The body must contain {@code
     * {"confirmation":"FLUSH"}} — typing this matches the destructive UX on the frontend, where the
     * user must type FLUSH into a text box. The action is loud-logged at WARN with the actor and
     * source IP so it shows up in audit log scans and triggers any log-level alerts.
     */
    @PostMapping("/cache/flush")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> flushCache(
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        Object conf = body == null ? null : body.get("confirmation");
        if (conf == null || !CACHE_FLUSH_CONFIRMATION.equals(conf.toString())) {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Confirmation phrase required: post"
                                            + " {\"confirmation\":\"FLUSH\"}"));
        }
        String actor = currentActor();
        String ip = clientIp(req);
        log.warn("ADMIN ACTION: cache flush requested — actor={} ip={}", actor, ip);
        try {
            long purged = cacheStatsService.flushAll();
            log.warn(
                    "ADMIN ACTION: cache flush completed — actor={} ip={} keysBefore={}",
                    actor,
                    ip,
                    purged);
            return ResponseEntity.ok(
                    Map.of(
                            "flushed",
                            true,
                            "keysBefore",
                            purged,
                            "actor",
                            actor == null ? "anonymous" : actor));
        } catch (Exception e) {
            log.error(
                    "ADMIN ACTION: cache flush FAILED — actor={} ip={} error={}",
                    actor,
                    ip,
                    e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Flush failed: " + e.getMessage()));
        }
    }

    // ---------------- helpers ----------------

    private static String currentActor() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }

    /**
     * Resolve the originating IP. Behind Cloudflare → nginx, Spring sees the proxy IP unless the
     * X-Forwarded-For header is honored. We trust the first hop here (server-internal nginx) and
     * fall back to the remote addr for safety. Audit-only — never use for auth.
     */
    private static String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return comma < 0 ? fwd.trim() : fwd.substring(0, comma).trim();
        }
        return req.getRemoteAddr();
    }
}
