package com.smmpanel.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CancelDecisionService {

    private static final String KEY_PREFIX = "telegram:cancel_pending:";
    private static final String CALLBACK_LOCK_PREFIX = "telegram:callback_lock:";
    private static final long CALLBACK_LOCK_TTL_SECONDS = 300;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final TelegramBotProperties telegramBotProperties;

    /**
     * Idempotency lock per Telegram update_id (not orderId — Telegram retries the same update_id
     * when our webhook response is slow). Returns true if the caller acquired the lock and should
     * process; false if the update was already processed or is being processed concurrently.
     */
    public boolean acquireCallbackLock(Long updateId) {
        if (updateId == null) return true;
        String key = CALLBACK_LOCK_PREFIX + updateId;
        Boolean ok =
                stringRedisTemplate
                        .opsForValue()
                        .setIfAbsent(key, "1", CALLBACK_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Store a pending cancel decision in Redis using SETNX (only if not already present). Returns
     * true if stored, false if a decision already existed for this order.
     */
    public boolean storePendingDecision(Long orderId, CancelPendingDecision decision) {
        String key = key(orderId);
        try {
            String json = objectMapper.writeValueAsString(decision);
            long timeoutHours = telegramBotProperties.getCancel().getTimeoutHours();
            Boolean stored =
                    stringRedisTemplate
                            .opsForValue()
                            .setIfAbsent(key, json, timeoutHours, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(stored)) {
                log.info("Stored cancel pending decision for order {}", orderId);
                return true;
            } else {
                log.info("Cancel pending decision already exists for order {}, skipping", orderId);
                return false;
            }
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to serialize CancelPendingDecision for order {}: {}",
                    orderId,
                    e.getMessage());
            return false;
        }
    }

    public Optional<CancelPendingDecision> getPendingDecision(Long orderId) {
        String json = stringRedisTemplate.opsForValue().get(key(orderId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, CancelPendingDecision.class));
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to deserialize CancelPendingDecision for order {}: {}",
                    orderId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    public void removePendingDecision(Long orderId) {
        stringRedisTemplate.delete(key(orderId));
        log.info("Removed cancel pending decision for order {}", orderId);
    }

    /**
     * Atomic claim: returns the decision and deletes it from Redis in a single round-trip. Used by
     * the admin "proceed/cancel" endpoints so that two concurrent operator clicks can't both
     * succeed (only one wins the DEL race; the other gets {@link Optional#empty()}).
     */
    public Optional<CancelPendingDecision> claimPendingDecision(Long orderId) {
        if (orderId == null) return Optional.empty();
        String json = stringRedisTemplate.opsForValue().getAndDelete(key(orderId));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, CancelPendingDecision.class));
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to deserialize CancelPendingDecision on claim for order {}: {}",
                    orderId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns list of orderIds whose decisions have NOT yet expired (still in Redis). Used by
     * scheduler to find decisions that need processing on TTL expiry via scan. Note: Redis
     * TTL-expired keys are already gone — this returns currently active ones.
     */
    public List<Long> getAllPendingOrderIds() {
        Set<String> keys = stringRedisTemplate.keys(KEY_PREFIX + "*");
        List<Long> orderIds = new ArrayList<>();
        if (keys == null) return orderIds;
        for (String k : keys) {
            Long ttl = stringRedisTemplate.getExpire(k, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                try {
                    String suffix = k.substring(KEY_PREFIX.length());
                    orderIds.add(Long.parseLong(suffix));
                } catch (NumberFormatException e) {
                    log.warn("Invalid cancel pending key: {}", k);
                }
            }
        }
        return orderIds;
    }

    private String key(Long orderId) {
        return KEY_PREFIX + orderId;
    }
}
