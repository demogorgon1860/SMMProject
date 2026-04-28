package com.smmpanel.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.admin.BotWebhookEventDto;
import com.smmpanel.dto.instagram.InstagramResultMessage;
import com.smmpanel.dto.instagram.InstagramWebhookCallback;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Records bot webhook/result events into Redis so the admin Bot page can show:
 *
 * <ul>
 *   <li>"Recent webhooks" via {@link #getRecent(int)} (Redis LIST {@code bot:webhooks:recent},
 *       newest first, capped at 200)
 *   <li>A live SSE feed via the pub/sub channel {@code bot:webhooks:stream}
 * </ul>
 *
 * <p>Both bot result paths (HTTP webhook and RabbitMQ consumer) call into this service so the admin
 * page is path-agnostic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotWebhookEventRecorder {

    public static final String LIST_KEY = "bot:webhooks:recent";
    public static final String STREAM_CHANNEL = "bot:webhooks:stream";
    public static final int MAX_EVENTS = 200;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** Record an event sourced from the HTTP webhook path. */
    public void recordWebhook(InstagramWebhookCallback callback) {
        BotWebhookEventDto dto =
                BotWebhookEventDto.builder()
                        .ts(Instant.now().toString())
                        .source("webhook")
                        .externalId(callback.getExternalId())
                        .botOrderId(callback.getId())
                        .event(callback.getEvent())
                        .status(callback.getStatus())
                        .completed(callback.getCompleted())
                        .failed(callback.getFailed())
                        .severity(severityOf(callback.getStatus()))
                        .build();
        publish(dto);
    }

    /** Record an event sourced from the RabbitMQ result consumer. */
    public void recordRabbitResult(InstagramResultMessage result) {
        BotWebhookEventDto dto =
                BotWebhookEventDto.builder()
                        .ts(Instant.now().toString())
                        .source("rabbitmq")
                        .externalId(result.getExternalId())
                        .botOrderId(result.getOrderId())
                        .event("order.result")
                        .status(result.getStatus())
                        .completed(result.getCompleted())
                        .failed(result.getFailed())
                        .message(result.getError())
                        .severity(severityOf(result.getStatus()))
                        .build();
        publish(dto);
    }

    /** Read the most recent N events (newest first). */
    public List<BotWebhookEventDto> getRecent(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_EVENTS));
        List<String> raw = stringRedisTemplate.opsForList().range(LIST_KEY, 0, capped - 1);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<BotWebhookEventDto> out = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                out.add(objectMapper.readValue(json, BotWebhookEventDto.class));
            } catch (Exception e) {
                log.warn("Skipping corrupt webhook event in {}: {}", LIST_KEY, e.getMessage());
            }
        }
        return out;
    }

    /**
     * Write to the LIST (LPUSH + LTRIM) and publish on the stream channel.
     *
     * <p>Failures are logged but not rethrown — recording is best-effort and must never break the
     * order processing path.
     */
    private void publish(BotWebhookEventDto dto) {
        String json;
        try {
            json = objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize bot webhook event: {}", e.getMessage());
            return;
        }

        try {
            stringRedisTemplate.opsForList().leftPush(LIST_KEY, json);
            stringRedisTemplate.opsForList().trim(LIST_KEY, 0, MAX_EVENTS - 1);
        } catch (Exception e) {
            log.warn("Failed to push bot webhook event to LIST {}: {}", LIST_KEY, e.getMessage());
        }

        try {
            stringRedisTemplate.convertAndSend(STREAM_CHANNEL, json);
        } catch (Exception e) {
            log.warn(
                    "Failed to publish bot webhook event on {}: {}",
                    STREAM_CHANNEL,
                    e.getMessage());
        }
    }

    private static String severityOf(String status) {
        if (status == null) return "info";
        return switch (status.toLowerCase()) {
            case "completed" -> "success";
            case "failed", "cancelled" -> "error";
            case "partial", "pending_cancel" -> "warn";
            default -> "info";
        };
    }
}
