package com.smmpanel.service.kafka;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Unified Message Idempotency Service for Kafka Consumer Idempotency
 *
 * <p>Implements message deduplication using Redis to ensure idempotent processing of Kafka
 * messages. Prevents duplicate processing of the same message even in case of consumer restarts or
 * rebalancing.
 *
 * <p>This service consolidates message deduplication functionality for all message types.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageIdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String KEY_PREFIX = "kafka:idempotency:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    // TTL for different message types
    private static final Duration ORDER_EVENTS_TTL = Duration.ofHours(24);
    private static final Duration VIDEO_PROCESSING_TTL = Duration.ofHours(48);
    private static final Duration PAYMENT_CONFIRMATION_TTL = Duration.ofHours(1);

    /**
     * Check if a message has already been processed.
     *
     * @param messageId unique message identifier
     * @return true if message was already processed, false otherwise
     */
    public boolean isDuplicate(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            log.warn("Invalid message ID provided for idempotency check");
            return false;
        }

        String key = KEY_PREFIX + messageId;
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Mark a message as processed.
     *
     * @param messageId unique message identifier
     */
    public void markAsProcessed(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            log.warn("Invalid message ID provided for marking as processed");
            return;
        }

        String key = KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, "processed", DEFAULT_TTL);
        log.debug("Marked message {} as processed with TTL {}", messageId, DEFAULT_TTL);
    }

    /**
     * Mark a message as processed with custom TTL.
     *
     * @param messageId unique message identifier
     * @param ttl time to live for the idempotency key
     */
    public void markAsProcessed(String messageId, Duration ttl) {
        if (messageId == null || messageId.isEmpty()) {
            log.warn("Invalid message ID provided for marking as processed");
            return;
        }

        String key = KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, "processed", ttl);
        log.debug("Marked message {} as processed with TTL {}", messageId, ttl);
    }

    /**
     * Remove idempotency key for a message (allows reprocessing).
     *
     * @param messageId unique message identifier
     */
    public void removeIdempotencyKey(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }

        String key = KEY_PREFIX + messageId;
        redisTemplate.delete(key);
        log.debug("Removed idempotency key for message {}", messageId);
    }

    /**
     * Check and mark atomically - ensures thread safety.
     *
     * @param messageId unique message identifier
     * @return true if message was new and marked, false if already processed
     */
    public boolean checkAndMark(String messageId) {
        if (messageId == null || messageId.isEmpty()) {
            log.warn("Invalid message ID provided for check and mark");
            return false;
        }

        String key = KEY_PREFIX + messageId;
        Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "processed", DEFAULT_TTL);

        if (Boolean.TRUE.equals(success)) {
            log.debug("Successfully marked new message {} as processed", messageId);
            return true;
        } else {
            log.debug("Message {} was already processed", messageId);
            return false;
        }
    }

    /**
     * Generate unique message ID from Kafka message metadata
     *
     * @param topic Topic name
     * @param partition Partition number
     * @param offset Message offset
     * @return Unique message identifier
     */
    public String generateMessageId(String topic, int partition, long offset) {
        return String.format("%s-%d-%d", topic, partition, offset);
    }

    /**
     * Check if an order event has already been processed
     *
     * @param messageId Unique message identifier
     * @param orderId Order ID for additional verification
     * @return true if message was already processed, false if it's new
     */
    public boolean isOrderEventAlreadyProcessed(String messageId, Long orderId) {
        String key = KEY_PREFIX + messageId;
        String existingOrderId = redisTemplate.opsForValue().get(key);

        if (existingOrderId != null) {
            if (orderId.toString().equals(existingOrderId)) {
                log.debug(
                        "Order event already processed: messageId={}, orderId={}",
                        messageId,
                        orderId);
                return true;
            }
        }
        return false;
    }

    /**
     * Mark an order event as processed
     *
     * @param messageId Unique message identifier
     * @param orderId Order ID
     */
    public void markOrderEventAsProcessed(String messageId, Long orderId) {
        String key = KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, orderId.toString(), ORDER_EVENTS_TTL);
        log.debug("Marked order event as processed: messageId={}, orderId={}", messageId, orderId);
    }

    /**
     * Check if a payment confirmation has already been processed
     *
     * @param messageId Unique message identifier
     * @param transactionId Transaction ID
     * @return true if message was already processed, false if it's new
     */
    public boolean isPaymentConfirmationAlreadyProcessed(String messageId, String transactionId) {
        String key = KEY_PREFIX + messageId;
        String existingTransactionId = redisTemplate.opsForValue().get(key);

        if (existingTransactionId != null) {
            if (transactionId.equals(existingTransactionId)) {
                log.debug(
                        "Payment confirmation already processed: messageId={}, transactionId={}",
                        messageId,
                        transactionId);
                return true;
            }
        }
        return false;
    }

    /**
     * Mark a payment confirmation as processed
     *
     * @param messageId Unique message identifier
     * @param transactionId Transaction ID
     */
    public void markPaymentConfirmationAsProcessed(String messageId, String transactionId) {
        String key = KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, transactionId, PAYMENT_CONFIRMATION_TTL);
        log.debug(
                "Marked payment confirmation as processed: messageId={}, transactionId={}",
                messageId,
                transactionId);
    }

    /**
     * Check if a video processing event has already been processed
     *
     * @param messageId Unique message identifier
     * @param videoProcessingId Video processing ID
     * @return true if message was already processed, false if it's new
     */
    public boolean isVideoProcessingAlreadyProcessed(String messageId, Long videoProcessingId) {
        String key = KEY_PREFIX + messageId;
        String existingId = redisTemplate.opsForValue().get(key);

        if (existingId != null) {
            if (videoProcessingId.toString().equals(existingId)) {
                log.debug(
                        "Video processing event already processed: messageId={},"
                                + " videoProcessingId={}",
                        messageId,
                        videoProcessingId);
                return true;
            }
        }
        return false;
    }

    /**
     * Mark a video processing event as processed
     *
     * @param messageId Unique message identifier
     * @param videoProcessingId Video processing ID
     */
    public void markVideoProcessingAsProcessed(String messageId, Long videoProcessingId) {
        String key = KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, videoProcessingId.toString(), VIDEO_PROCESSING_TTL);
        log.debug(
                "Marked video processing event as processed: messageId={}, videoProcessingId={}",
                messageId,
                videoProcessingId);
    }
}
