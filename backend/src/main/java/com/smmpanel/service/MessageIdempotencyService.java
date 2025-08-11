package com.smmpanel.service;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Message Idempotency Service Handles duplicate message detection using Redis as a distributed
 * idempotency store
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageIdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String IDEMPOTENCY_KEY_PREFIX = "msg:idempotency:";
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    /**
     * Check if a message has been processed before
     *
     * @param messageId Unique message identifier
     * @param timestamp Message timestamp
     * @return true if message is a duplicate, false otherwise
     */
    public boolean isDuplicate(String messageId, LocalDateTime timestamp) {
        String key = IDEMPOTENCY_KEY_PREFIX + messageId;
        Boolean wasAbsent =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(key, timestamp.toString(), DEFAULT_RETENTION);

        if (Boolean.FALSE.equals(wasAbsent)) {
            log.warn("Detected duplicate message: messageId={}", messageId);
            return true;
        }
        return false;
    }

    /**
     * Mark a message as processed successfully
     *
     * @param messageId Unique message identifier
     * @param timestamp Message timestamp
     */
    public void markAsProcessed(String messageId, LocalDateTime timestamp) {
        String key = IDEMPOTENCY_KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, timestamp.toString(), DEFAULT_RETENTION);
        log.debug("Marked message as processed: messageId={}", messageId);
    }
}
