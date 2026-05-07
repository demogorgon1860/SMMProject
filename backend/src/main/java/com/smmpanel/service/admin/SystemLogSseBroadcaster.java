package com.smmpanel.service.admin;

import com.smmpanel.monitoring.RedisLogSink;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bridges the Redis pub/sub channel {@link RedisLogSink#STREAM_CHANNEL} into a set of live SSE
 * emitters that the {@code /admin/system} Logs tab subscribes to.
 *
 * <p>Mirrors the design of {@link BotWebhookSseBroadcaster}: one shared Redis subscription, a
 * concurrent set of emitters, self-cleanup on completion / timeout / error so closed browser tabs
 * don't leak server resources.
 */
@Slf4j
@Service
public class SystemLogSseBroadcaster implements MessageListener {

    /**
     * 30 minutes — long enough that an idle admin doesn't see the stream blink, short enough that a
     * forgotten tab eventually frees its async servlet thread on the panel side.
     */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    public SystemLogSseBroadcaster(RedisMessageListenerContainer redisMessageListenerContainer) {
        this.redisMessageListenerContainer = redisMessageListenerContainer;
    }

    @PostConstruct
    void start() {
        redisMessageListenerContainer.addMessageListener(
                this, new ChannelTopic(RedisLogSink.STREAM_CHANNEL));
        log.info(
                "SystemLogSseBroadcaster subscribed to Redis channel {}",
                RedisLogSink.STREAM_CHANNEL);
    }

    @PreDestroy
    void stop() {
        try {
            redisMessageListenerContainer.removeMessageListener(this);
        } catch (Exception ignored) {
            // container may already be stopped
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter may already be closed
            }
        }
        emitters.clear();
    }

    /** Register a new SSE subscriber. Self-cleans on disconnect. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);

        Runnable cleanup =
                () -> {
                    emitters.remove(emitter);
                    log.debug("Log SSE emitter removed (active={})", emitters.size());
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(
                e -> {
                    log.debug("Log SSE emitter error: {}", e.getMessage());
                    cleanup.run();
                });

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            cleanup.run();
        }
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (emitters.isEmpty()) return;
        byte[] body = message.getBody();
        if (body == null || body.length == 0) return;
        String json = new String(body, StandardCharsets.UTF_8);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("log").data(json, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }
    }

    /**
     * Periodic SSE comment so idle log-tail connections don't get closed by intermediaries.
     * Mirrors {@link BotWebhookSseBroadcaster#heartbeat()} — same Cloudflare HTTP/3 idle-kill
     * problem, same solution: one comment frame every 25s keeps the path warm.
     */
    @Scheduled(fixedDelay = 25_000L, initialDelay = 25_000L)
    public void heartbeat() {
        if (emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }
    }
}
