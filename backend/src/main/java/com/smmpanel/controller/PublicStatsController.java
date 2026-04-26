package com.smmpanel.controller;

import com.smmpanel.service.stats.PublicStatsService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated stats endpoint. Powers the landing-page hero metrics.
 *
 * <p>Every value is sourced from a real DB aggregate via {@link PublicStatsService} (which owns the
 * {@code @Cacheable} so the proxy boundary actually applies). We deliberately do NOT publish
 * synthetic uptime, hand-tuned average start time, or any marketing-friendly numbers — if a metric
 * isn't something we can measure honestly today, it's not in the response.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PublicStatsController {

    private final PublicStatsService publicStatsService;

    /**
     * Landing-page metrics. Server-side cached for 60s in Redis ({@code public-stats}); the HTTP
     * response also carries {@code Cache-Control: public, max-age=60} so any CDN or browser reuses
     * it without round-tripping. End-to-end this is one set of count queries per minute, not per
     * visitor.
     */
    @GetMapping("/stats/public")
    public ResponseEntity<Map<String, Object>> publicStats() {
        Map<String, Object> body = publicStatsService.compute();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(body);
    }

    /**
     * Recent orders for the landing-page ticker. Sanitized — ID + quantity + short service name +
     * status + age, no usernames/URLs/charges. Cached server-side (Redis 10s) and via HTTP
     * Cache-Control so the landing handles spikes without DB pressure.
     */
    @GetMapping("/stats/recent-orders")
    public ResponseEntity<List<Map<String, Object>>> recentOrders() {
        List<Map<String, Object>> body = publicStatsService.recentOrders();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).cachePublic())
                .body(body);
    }
}
