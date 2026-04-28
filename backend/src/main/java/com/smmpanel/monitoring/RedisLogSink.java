package com.smmpanel.monitoring;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Bridges {@link LogbackRedisAppender} to Redis.
 *
 * <p>On startup, registers itself with the appender via {@link LogbackRedisAppender#setSink}. From
 * that point every log event is buffered in the appender's in-memory queue, drained on a single
 * background thread, and shipped to:
 *
 * <ul>
 *   <li>{@link #LIST_KEY} — capped LIST (newest at head) of recent events. Read by {@code
 *       SystemLogService#getRecent}.
 *   <li>{@link #STREAM_CHANNEL} — pub/sub channel that {@link
 *       com.smmpanel.service.admin.SystemLogSseBroadcaster} subscribes to for the live tail.
 * </ul>
 *
 * <p>This sink runs on the appender's drain thread, NEVER on the request thread, so a slow LPUSH
 * cannot stall the application. Failures here are caught by the appender and surface only via
 * Logback's status manager (System.err) — never via SLF4J, which would feedback-loop.
 */
@Component
@RequiredArgsConstructor
public class RedisLogSink {

    /** Redis LIST holding the most recent N log events as JSON strings (newest at index 0). */
    public static final String LIST_KEY = "app:logs:recent";

    /** Pub/sub channel for live SSE tail. */
    public static final String STREAM_CHANNEL = "app:logs:stream";

    /** Hard cap on entries kept in the LIST. ~500 × ~400B = ~200 KB. */
    public static final int LIST_CAP = 500;

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.system-logs.list-cap:500}")
    private int listCap;

    private LogbackRedisAppender.Sink boundSink;

    @PostConstruct
    void register() {
        // Capture as a final field so clearSink in @PreDestroy can null only this exact sink and
        // not e.g. one another instance reset since (matters during dev hot-reload / tests).
        boundSink = json -> push(json);
        LogbackRedisAppender.setSink(boundSink);
    }

    @PreDestroy
    void unregister() {
        if (boundSink != null) {
            LogbackRedisAppender.clearSink(boundSink);
            boundSink = null;
        }
    }

    /**
     * Write one event to Redis (LPUSH + LTRIM + PUBLISH). Throws on Redis error so the appender's
     * drain loop reports it via Logback's status manager.
     *
     * <p>NOT idempotent — same event written twice = two LIST entries. The drain loop is
     * single-threaded so this isn't a concern in practice.
     */
    private void push(String json) {
        int cap = listCap > 0 ? listCap : LIST_CAP;
        // LPUSH + LTRIM is the canonical capped log buffer pattern. We don't pipeline — per-event
        // round-trip latency is fine on a local Redis, and pipelining risks losing the tail when
        // the JVM dies mid-flush.
        stringRedisTemplate.opsForList().leftPush(LIST_KEY, json);
        stringRedisTemplate.opsForList().trim(LIST_KEY, 0, cap - 1);
        stringRedisTemplate.convertAndSend(STREAM_CHANNEL, json);
    }
}
