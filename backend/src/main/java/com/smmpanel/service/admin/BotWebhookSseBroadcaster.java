package com.smmpanel.service.admin;

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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Bridges the Redis pub/sub channel {@link BotWebhookEventRecorder#STREAM_CHANNEL} into a set of
 * live SSE emitters. New emitters are created via {@link #subscribe()} and self-clean on completion
 * / timeout / error so that closed browser tabs don't leak Redis subscriptions.
 *
 * <p>A single Redis subscription is shared across all emitters — adding/removing emitters does not
 * touch the Redis listener.
 */
@Slf4j
@Service
public class BotWebhookSseBroadcaster implements MessageListener {

    /** SSE keep-alive timeout — must be longer than typical idle, but bounded. */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final Set<SseEmitter> emitters = new CopyOnWriteArraySet<>();

    public BotWebhookSseBroadcaster(RedisMessageListenerContainer redisMessageListenerContainer) {
        this.redisMessageListenerContainer = redisMessageListenerContainer;
    }

    @PostConstruct
    void start() {
        redisMessageListenerContainer.addMessageListener(
                this, new ChannelTopic(BotWebhookEventRecorder.STREAM_CHANNEL));
        log.info(
                "BotWebhookSseBroadcaster subscribed to Redis channel {}",
                BotWebhookEventRecorder.STREAM_CHANNEL);
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

    /** Register a new SSE subscriber. The returned emitter is wired to live bot webhook events. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitters.add(emitter);

        Runnable cleanup =
                () -> {
                    emitters.remove(emitter);
                    log.debug("SSE emitter removed (active={})", emitters.size());
                };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(
                e -> {
                    log.debug("SSE emitter error: {}", e.getMessage());
                    cleanup.run();
                });

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            cleanup.run();
        }
        log.debug("SSE emitter added (active={})", emitters.size());
        return emitter;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (emitters.isEmpty()) {
            return;
        }
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return;
        }
        String json = new String(body, StandardCharsets.UTF_8);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(
                        SseEmitter.event().name("webhook").data(json, MediaType.APPLICATION_JSON));
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
