package com.smmpanel.service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Message Deduplication Service for Kafka Consumer Idempotency
 * 
 * Implements message deduplication using Redis to ensure idempotent processing
 * of Kafka messages. Prevents duplicate processing of the same message even
 * in case of consumer restarts or rebalancing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDeduplicationService {

    private final RedisTemplate<String, String> redisTemplate;
    
    // TTL for deduplication keys - should be longer than max processing time
    private static final Duration ORDER_EVENTS_TTL = Duration.ofHours(24);
    private static final Duration VIDEO_PROCESSING_TTL = Duration.ofHours(48);
    private static final Duration PAYMENT_CONFIRMATION_TTL = Duration.ofHours(1);
    
    // Key prefixes for different message types
    private static final String ORDER_EVENT_PREFIX = "kafka:dedup:order:";
    private static final String VIDEO_PROCESSING_PREFIX = "kafka:dedup:video:";
    private static final String PAYMENT_CONFIRMATION_PREFIX = "kafka:dedup:payment:";

    /**
     * Check if an order event has already been processed
     * @param messageId Unique message identifier (topic + partition + offset)
     * @param orderId Order ID for additional verification
     * @return true if message was already processed, false if it's new
     */
    public boolean isOrderEventAlreadyProcessed(String messageId, Long orderId) {
        String key = ORDER_EVENT_PREFIX + messageId;
        String existingOrderId = redisTemplate.opsForValue().get(key);
        
        if (existingOrderId != null) {
            // Verify the order ID matches to prevent hash collisions
            if (orderId.toString().equals(existingOrderId)) {
                log.debug("Order event already processed: messageId={}, orderId={}", messageId, orderId);
                return true;
            } else {
                log.warn("Message ID collision detected: messageId={}, stored orderId={}, current orderId={}", 
                    messageId, existingOrderId, orderId);
            }
        }
        
        return false;
    }

    /**
     * Mark an order event as processed
     * @param messageId Unique message identifier
     * @param orderId Order ID
     */
    public void markOrderEventAsProcessed(String messageId, Long orderId) {
        String key = ORDER_EVENT_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, orderId.toString(), ORDER_EVENTS_TTL);
        log.debug("Marked order event as processed: messageId={}, orderId={}", messageId, orderId);
    }

    /**
     * Check if a video processing event has already been processed
     * @param messageId Unique message identifier
     * @param videoProcessingId Video processing ID
     * @return true if message was already processed, false if it's new
     */
    public boolean isVideoProcessingAlreadyProcessed(String messageId, Long videoProcessingId) {
        String key = VIDEO_PROCESSING_PREFIX + messageId;
        String existingId = redisTemplate.opsForValue().get(key);
        
        if (existingId != null) {
            if (videoProcessingId.toString().equals(existingId)) {
                log.debug("Video processing event already processed: messageId={}, videoProcessingId={}", 
                    messageId, videoProcessingId);
                return true;
            } else {
                log.warn("Video processing message ID collision: messageId={}, stored ID={}, current ID={}", 
                    messageId, existingId, videoProcessingId);
            }
        }
        
        return false;
    }

    /**
     * Mark a video processing event as processed
     * @param messageId Unique message identifier
     * @param videoProcessingId Video processing ID
     */
    public void markVideoProcessingAsProcessed(String messageId, Long videoProcessingId) {
        String key = VIDEO_PROCESSING_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, videoProcessingId.toString(), VIDEO_PROCESSING_TTL);
        log.debug("Marked video processing event as processed: messageId={}, videoProcessingId={}", 
            messageId, videoProcessingId);
    }

    /**
     * Check if a payment confirmation has already been processed
     * @param messageId Unique message identifier
     * @param transactionId Transaction ID
     * @return true if message was already processed, false if it's new
     */
    public boolean isPaymentConfirmationAlreadyProcessed(String messageId, String transactionId) {
        String key = PAYMENT_CONFIRMATION_PREFIX + messageId;
        String existingTransactionId = redisTemplate.opsForValue().get(key);
        
        if (existingTransactionId != null) {
            if (transactionId.equals(existingTransactionId)) {
                log.debug("Payment confirmation already processed: messageId={}, transactionId={}", 
                    messageId, transactionId);
                return true;
            } else {
                log.warn("Payment confirmation message ID collision: messageId={}, stored txId={}, current txId={}", 
                    messageId, existingTransactionId, transactionId);
            }
        }
        
        return false;
    }

    /**
     * Mark a payment confirmation as processed
     * @param messageId Unique message identifier
     * @param transactionId Transaction ID
     */
    public void markPaymentConfirmationAsProcessed(String messageId, String transactionId) {
        String key = PAYMENT_CONFIRMATION_PREFIX + messageId;
        redisTemplate.opsForValue().set(key, transactionId, PAYMENT_CONFIRMATION_TTL);
        log.debug("Marked payment confirmation as processed: messageId={}, transactionId={}", 
            messageId, transactionId);
    }

    /**
     * Generate unique message ID from Kafka message metadata
     * @param topic Topic name
     * @param partition Partition number
     * @param offset Message offset
     * @return Unique message identifier
     */
    public String generateMessageId(String topic, int partition, long offset) {
        return String.format("%s-%d-%d", topic, partition, offset);
    }

    /**
     * Generate unique message ID with timestamp for additional uniqueness
     * @param topic Topic name
     * @param partition Partition number
     * @param offset Message offset
     * @param timestamp Message timestamp
     * @return Unique message identifier with timestamp
     */
    public String generateMessageIdWithTimestamp(String topic, int partition, long offset, long timestamp) {
        return String.format("%s-%d-%d-%d", topic, partition, offset, timestamp);
    }

    /**
     * Check if any message type has already been processed (generic method)
     * @param messageType Type of message (order, video, payment)
     * @param messageId Unique message identifier
     * @param entityId Entity ID for verification
     * @return true if already processed, false otherwise
     */
    public boolean isMessageAlreadyProcessed(String messageType, String messageId, String entityId) {
        String prefix = switch (messageType.toLowerCase()) {
            case "order" -> ORDER_EVENT_PREFIX;
            case "video" -> VIDEO_PROCESSING_PREFIX;
            case "payment" -> PAYMENT_CONFIRMATION_PREFIX;
            default -> "kafka:dedup:unknown:";
        };
        
        String key = prefix + messageId;
        String existingEntityId = redisTemplate.opsForValue().get(key);
        
        if (existingEntityId != null && entityId.equals(existingEntityId)) {
            log.debug("Message already processed: type={}, messageId={}, entityId={}", 
                messageType, messageId, entityId);
            return true;
        }
        
        return false;
    }

    /**
     * Mark any message type as processed (generic method)
     * @param messageType Type of message (order, video, payment)
     * @param messageId Unique message identifier
     * @param entityId Entity ID
     * @param ttl Time to live for the deduplication record
     */
    public void markMessageAsProcessed(String messageType, String messageId, String entityId, Duration ttl) {
        String prefix = switch (messageType.toLowerCase()) {
            case "order" -> ORDER_EVENT_PREFIX;
            case "video" -> VIDEO_PROCESSING_PREFIX;
            case "payment" -> PAYMENT_CONFIRMATION_PREFIX;
            default -> "kafka:dedup:unknown:";
        };
        
        String key = prefix + messageId;
        redisTemplate.opsForValue().set(key, entityId, ttl);
        log.debug("Marked message as processed: type={}, messageId={}, entityId={}, ttl={}", 
            messageType, messageId, entityId, ttl);
    }

    /**
     * Clean up expired deduplication records (manual cleanup if needed)
     * This is typically handled automatically by Redis TTL, but can be called manually
     */
    public void cleanupExpiredRecords() {
        try {
            // Redis handles TTL automatically, but we can log cleanup activities
            long orderKeys = countKeysByPattern(ORDER_EVENT_PREFIX + "*");
            long videoKeys = countKeysByPattern(VIDEO_PROCESSING_PREFIX + "*");
            long paymentKeys = countKeysByPattern(PAYMENT_CONFIRMATION_PREFIX + "*");
            
            log.info("Deduplication cache status - Order keys: {}, Video keys: {}, Payment keys: {}", 
                orderKeys, videoKeys, paymentKeys);
                
        } catch (Exception e) {
            log.error("Error during deduplication cache cleanup", e);
        }
    }

    /**
     * Count keys matching a pattern (for monitoring purposes)
     */
    private long countKeysByPattern(String pattern) {
        try {
            return redisTemplate.keys(pattern).size();
        } catch (Exception e) {
            log.warn("Error counting keys for pattern: {}", pattern, e);
            return -1;
        }
    }

    /**
     * Get statistics about deduplication cache usage
     * @return Map of cache statistics
     */
    public java.util.Map<String, Long> getDeduplicationStats() {
        return java.util.Map.of(
            "orderEventKeys", countKeysByPattern(ORDER_EVENT_PREFIX + "*"),
            "videoProcessingKeys", countKeysByPattern(VIDEO_PROCESSING_PREFIX + "*"),
            "paymentConfirmationKeys", countKeysByPattern(PAYMENT_CONFIRMATION_PREFIX + "*")
        );
    }
}