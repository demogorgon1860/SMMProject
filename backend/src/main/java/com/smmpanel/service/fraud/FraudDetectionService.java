package com.smmpanel.service.fraud;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.validation.ValidationResult;
import com.smmpanel.entity.Order;
import com.smmpanel.repository.jpa.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FraudDetectionService {

    private final OrderRepository orderRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String RATE_LIMIT_PREFIX = "fraud:rate:";
    private static final String DUPLICATE_PREFIX = "fraud:duplicate:";
    private static final int RATE_LIMIT = 5; // Max 5 orders per minute
    private static final int DUPLICATE_WINDOW_MINUTES =
            10; // 10 minutes window for duplicate detection
    private static final int SUSPICIOUS_ORDERS_PER_HOUR = 20; // Threshold for suspicious orders
    private static final int REPEATED_QUANTITY_THRESHOLD =
            10; // Threshold for repeated quantity pattern

    public ValidationResult analyzeOrder(Long userId, CreateOrderRequest request) {
        ValidationResult result = ValidationResult.builder().build();

        // 1. Rate limiting check
        if (isRateLimitExceeded(userId)) {
            result.addError("rate_limit", "Too many orders in a short time period");
        }

        // 2. Duplicate order detection
        if (isDuplicateOrder(userId, request)) {
            result.addError("duplicate", "Similar order already exists");
        }

        // 3. Suspicious pattern detection
        if (isSuspiciousPattern(userId, request)) {
            result.addError("suspicious", "Order flagged for manual review");
        }

        // 4. High-value order check
        if (isHighValueOrder(request) && !isVerifiedUser(userId)) {
            result.addError("verification", "High-value orders require verification");
        }

        return result;
    }

    private boolean isRateLimitExceeded(Long userId) {
        String key = RATE_LIMIT_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) {
            return false; // Failed to check rate limit, allow the request
        }

        if (count == 1) {
            // Set expiration on first increment
            redisTemplate.expire(key, 1, TimeUnit.MINUTES);
        }

        return count > RATE_LIMIT;
    }

    private boolean isDuplicateOrder(Long userId, CreateOrderRequest request) {
        String key = DUPLICATE_PREFIX + userId + ":" + request.getLink();

        // Check if we've seen this recently in Redis
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return true;
        }

        // Check database for recent orders with same link
        LocalDateTime since = LocalDateTime.now().minusMinutes(DUPLICATE_WINDOW_MINUTES);
        long duplicateCount =
                orderRepository.countByUserIdAndLinkAndCreatedAtAfter(
                        userId, request.getLink(), since);

        // Cache the result to prevent repeated DB queries
        if (duplicateCount > 0) {
            redisTemplate.opsForValue().set(key, "1", DUPLICATE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }

        return duplicateCount > 0;
    }

    private boolean isSuspiciousPattern(Long userId, CreateOrderRequest request) {
        // Check for patterns indicating bot behavior
        LocalDateTime lastHour = LocalDateTime.now().minusHours(1);
        List<Order> recentOrders = orderRepository.findByUserIdAndCreatedAtAfter(userId, lastHour);

        // Check for too many orders in a short time
        if (recentOrders.size() > SUSPICIOUS_ORDERS_PER_HOUR) {
            return true;
        }

        // Check for same quantity patterns
        if (!recentOrders.isEmpty()) {
            long sameQuantityCount =
                    recentOrders.stream()
                            .filter(order -> order.getQuantity().equals(request.getQuantity()))
                            .count();

            if (sameQuantityCount >= REPEATED_QUANTITY_THRESHOLD) {
                return true;
            }
        }

        return false;
    }

    private boolean isHighValueOrder(CreateOrderRequest request) {
        // Orders over $100 are considered high-value
        // Assuming $1 per 1000 views as baseline
        return request.getQuantity() > 100000;
    }

    private boolean isVerifiedUser(Long userId) {
        // In a real implementation, this would check user verification status
        // For now, we'll assume all users with ID > 1000 are verified
        // This should be replaced with actual user verification check
        return userId != null && userId > 1000;
    }
}
