package com.smmpanel.service.order;

import com.smmpanel.entity.Order;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.integration.YouTubeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate service for async YouTube startCount capture. Must be a separate bean from OrderService
 * so that Spring AOP proxy correctly intercepts @Async calls.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class YouTubeStartCountService {

    private final OrderRepository orderRepository;
    private final YouTubeService youTubeService;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final int REDIS_PROGRESS_TTL_HOURS = 48;

    @Async("asyncExecutor")
    @Transactional
    public void captureStartCountAsync(Long orderId, String link) {
        try {
            String videoId = extractVideoId(link);
            log.info(
                    "Order {} - Async capturing YouTube startCount, video ID: {}",
                    orderId,
                    videoId);

            if (videoId != null) {
                int startCount = youTubeService.getVideoViewCount(videoId);
                Order order = orderRepository.findById(orderId).orElse(null);
                if (order != null) {
                    order.setStartCount(startCount);
                    order.setYoutubeVideoId(videoId);
                    orderRepository.save(order);
                    cacheStartCount(orderId, videoId, startCount);
                    log.info("Order {} - YouTube startCount captured: {}", orderId, startCount);
                }
            } else {
                log.warn("Order {} - Could not extract video ID from URL: {}", orderId, link);
            }
        } catch (Exception e) {
            log.error("Failed to capture startCount for order {}: {}", orderId, e.getMessage());
        }
    }

    private String extractVideoId(String url) {
        if (url == null) return null;

        if (url.contains("youtube.com/watch")) {
            String[] parts = url.split("[?&]");
            for (String part : parts) {
                if (part.startsWith("v=")) {
                    return part.substring(2).split("[&]")[0];
                }
            }
        }

        if (url.contains("youtu.be/")) {
            String[] parts = url.split("youtu.be/");
            if (parts.length > 1) {
                return parts[1].split("[?&]")[0];
            }
        }

        if (url.contains("youtube.com/shorts/")) {
            String[] parts = url.split("shorts/");
            if (parts.length > 1) {
                return parts[1].split("[?&]")[0];
            }
        }

        return null;
    }

    private void cacheStartCount(Long orderId, String videoId, int startCount) {
        try {
            String cacheKey = "order:progress:" + orderId;
            java.util.Map<String, Object> progress = new java.util.HashMap<>();
            progress.put("videoId", videoId);
            progress.put("startCount", String.valueOf(startCount));
            progress.put("capturedAt", java.time.LocalDateTime.now().toString());
            redisTemplate.opsForHash().putAll(cacheKey, progress);
            redisTemplate.expire(cacheKey, java.time.Duration.ofHours(REDIS_PROGRESS_TTL_HOURS));
        } catch (Exception e) {
            log.warn("Failed to cache startCount for order {}: {}", orderId, e.getMessage());
        }
    }
}
