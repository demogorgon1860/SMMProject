package com.smmpanel.service.integration;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.instagram.*;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.instagram.InstagramRabbitPublisher;
import com.smmpanel.service.notification.TelegramNotificationService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing Instagram orders through the Instagram bot. Handles order creation, status
 * tracking, and webhook callbacks.
 */
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class InstagramService {

    private final InstagramBotClient botClient;
    private final OrderRepository orderRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TelegramNotificationService telegramNotificationService;
    private final InstagramRabbitPublisher rabbitPublisher;

    @Value("${app.instagram.use-rabbitmq:true}")
    private boolean useRabbitMQ;

    private static final String INSTAGRAM_ORDER_CACHE_PREFIX = "instagram:order:";
    private static final String INSTAGRAM_BOT_ORDER_ID_PREFIX = "instagram:bot_order:";

    /** Check if a service is an Instagram service. */
    public boolean isInstagramService(Service service) {
        if (service == null || service.getCategory() == null) {
            return false;
        }
        String category = service.getCategory().toUpperCase();
        return category.contains("INSTAGRAM");
    }

    /**
     * Create an Instagram order in the bot. Uses RabbitMQ for geo-based routing if enabled,
     * otherwise falls back to HTTP.
     *
     * @param order The panel order
     * @return Bot order response
     */
    @Transactional
    public InstagramOrderResponse createInstagramOrder(Order order) {
        log.info(
                "Creating Instagram order for panel order {} (useRabbitMQ={})",
                order.getId(),
                useRabbitMQ);

        try {
            // Validate service
            Service service = order.getService();
            if (!isInstagramService(service)) {
                log.error("Service {} is not an Instagram service", service.getId());
                return InstagramOrderResponse.builder()
                        .success(false)
                        .error("Not an Instagram service")
                        .build();
            }

            // Use RabbitMQ if enabled (for geo-based routing)
            if (useRabbitMQ) {
                return createInstagramOrderViaRabbitMQ(order, service);
            } else {
                return createInstagramOrderViaHttp(order, service);
            }

        } catch (IllegalArgumentException e) {
            log.error(
                    "Invalid Instagram service category for order {}: {}",
                    order.getId(),
                    e.getMessage());
            return InstagramOrderResponse.builder()
                    .success(false)
                    .error("Invalid service type: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Error creating Instagram order {}: {}", order.getId(), e.getMessage(), e);

            // Update order with error
            order.setErrorMessage("Instagram bot error: " + e.getMessage());
            order.setLastErrorType("BOT_ERROR");
            orderRepository.save(order);

            return InstagramOrderResponse.builder()
                    .success(false)
                    .error("Instagram bot error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Create Instagram order via RabbitMQ for geo-based routing. Orders are routed to KR or DE
     * queues based on service geo_targeting.
     */
    private InstagramOrderResponse createInstagramOrderViaRabbitMQ(Order order, Service service) {
        String geo = service.getGeoTargeting() != null ? service.getGeoTargeting() : "DE";

        log.info("Publishing Instagram order {} to RabbitMQ queue for geo: {}", order.getId(), geo);

        boolean published = rabbitPublisher.publishOrder(order, service);

        if (published) {
            // Update order status
            order.setStatus(OrderStatus.PROCESSING);
            order.setTrafficStatus("SENT_TO_RABBITMQ_" + geo.toUpperCase());
            orderRepository.save(order);

            log.info(
                    "Instagram order {} published to RabbitMQ queue instagram.orders.{}",
                    order.getId(),
                    geo.toLowerCase());

            return InstagramOrderResponse.builder()
                    .success(true)
                    .id("rabbitmq:" + order.getId())
                    .build();
        } else {
            order.setErrorMessage("Failed to publish to RabbitMQ");
            order.setRetryCount(order.getRetryCount() != null ? order.getRetryCount() + 1 : 1);
            orderRepository.save(order);

            return InstagramOrderResponse.builder()
                    .success(false)
                    .error("Failed to publish to RabbitMQ")
                    .build();
        }
    }

    /** Create Instagram order via direct HTTP call to bot (legacy method). */
    private InstagramOrderResponse createInstagramOrderViaHttp(Order order, Service service) {
        // Determine order type from service category
        InstagramOrderType orderType =
                InstagramOrderType.fromServiceCategory(service.getCategory());

        // Determine profile group from service geo-targeting
        String profileGroup = determineProfileGroup(service);

        // Build request
        InstagramOrderRequest request =
                InstagramOrderRequest.builder()
                        .type(orderType.getValue())
                        .targetUrl(order.getLink())
                        .count(order.getQuantity())
                        .externalId(String.valueOf(order.getId()))
                        .callbackUrl(botClient.getCallbackUrl())
                        .priority(
                                order.getProcessingPriority() != null
                                        ? order.getProcessingPriority()
                                        : 0)
                        .profileGroup(profileGroup)
                        .build();

        // Send to bot
        InstagramOrderResponse response = botClient.createOrder(request);

        if (response.isSuccess()) {
            // Store bot order ID in entity and Redis
            order.setInstagramBotOrderId(response.getId());
            String cacheKey = INSTAGRAM_BOT_ORDER_ID_PREFIX + order.getId();
            redisTemplate.opsForValue().set(cacheKey, response.getId());

            // Update order status
            order.setStatus(OrderStatus.PROCESSING);
            order.setTrafficStatus("SENT_TO_BOT");
            orderRepository.save(order);

            log.info(
                    "Instagram order created via HTTP: panel={}, bot={}",
                    order.getId(),
                    response.getId());
        } else {
            // Update order with error
            order.setErrorMessage("Instagram bot error: " + response.getError());
            order.setRetryCount(order.getRetryCount() != null ? order.getRetryCount() + 1 : 1);
            orderRepository.save(order);

            log.error(
                    "Failed to create Instagram order {}: {}", order.getId(), response.getError());
        }

        return response;
    }

    /**
     * Process webhook callback from Instagram bot.
     *
     * @param callback The callback data
     */
    @Transactional
    public void processWebhookCallback(InstagramWebhookCallback callback) {
        log.info(
                "Processing Instagram webhook callback: event={}, external_id={}, status={}",
                callback.getEvent(),
                callback.getExternalId(),
                callback.getStatus());

        try {
            // Find the order by external_id (our order ID)
            Long orderId = Long.parseLong(callback.getExternalId());
            Optional<Order> orderOpt = orderRepository.findById(orderId);

            if (orderOpt.isEmpty()) {
                log.warn("Order not found for Instagram callback: {}", callback.getExternalId());
                return;
            }

            Order order = orderOpt.get();

            // Update order based on callback status
            if ("completed".equalsIgnoreCase(callback.getStatus())) {
                handleOrderCompleted(order, callback);
            } else if ("failed".equalsIgnoreCase(callback.getStatus())) {
                handleOrderFailed(order, callback);
            } else {
                log.warn("Unknown callback status: {}", callback.getStatus());
            }

        } catch (NumberFormatException e) {
            log.error("Invalid external_id in callback: {}", callback.getExternalId());
        } catch (Exception e) {
            log.error("Error processing Instagram webhook callback: {}", e.getMessage(), e);
        }
    }

    /** Handle completed order callback. */
    private void handleOrderCompleted(Order order, InstagramWebhookCallback callback) {
        log.info(
                "Instagram order {} completed: {} actions done, {} failed",
                order.getId(),
                callback.getCompleted(),
                callback.getFailed());

        // Calculate actual delivered count
        int actualDelivered = callback.getCompleted() != null ? callback.getCompleted() : 0;
        int failedCount = callback.getFailed() != null ? callback.getFailed() : 0;

        // Save all Instagram count fields from callback
        if (callback.getStartLikeCount() != null) {
            order.setStartLikeCount(callback.getStartLikeCount());
        }
        if (callback.getStartFollowerCount() != null) {
            order.setStartFollowerCount(callback.getStartFollowerCount());
        }
        if (callback.getStartCommentCount() != null) {
            order.setStartCommentCount(callback.getStartCommentCount());
        }
        if (callback.getCurrentLikeCount() != null) {
            order.setCurrentLikeCount(callback.getCurrentLikeCount());
        }
        if (callback.getCurrentFollowerCount() != null) {
            order.setCurrentFollowerCount(callback.getCurrentFollowerCount());
        }
        if (callback.getCurrentCommentCount() != null) {
            order.setCurrentCommentCount(callback.getCurrentCommentCount());
        }

        // Set legacy start count from callback for backwards compatibility
        Integer startCount = determineStartCount(order, callback);
        if (startCount != null) {
            order.setStartCount(startCount);
        }

        // Update views delivered
        order.setViewsDelivered(actualDelivered);

        // Calculate remains
        int ordered = order.getQuantity();
        int remains = Math.max(0, ordered - actualDelivered);
        order.setRemains(remains);

        // Determine final status
        if (remains == 0 || actualDelivered >= ordered) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setTrafficStatus("COMPLETED");
            log.info("Order {} fully completed", order.getId());
        } else if (actualDelivered > 0) {
            // Partial completion
            order.setStatus(OrderStatus.PARTIAL);
            order.setTrafficStatus("PARTIAL");
            log.info(
                    "Order {} partially completed: {}/{} delivered",
                    order.getId(),
                    actualDelivered,
                    ordered);
        }

        order.setErrorMessage(null);
        orderRepository.save(order);

        // Clear cache
        String cacheKey = INSTAGRAM_BOT_ORDER_ID_PREFIX + order.getId();
        redisTemplate.delete(cacheKey);

        // Send Telegram notification
        telegramNotificationService.notifyOrderCompleted(order, actualDelivered);
    }

    /** Handle failed order callback. */
    private void handleOrderFailed(Order order, InstagramWebhookCallback callback) {
        log.warn(
                "Instagram order {} failed: {} completed, {} failed",
                order.getId(),
                callback.getCompleted(),
                callback.getFailed());

        // Save all Instagram count fields from callback
        if (callback.getStartLikeCount() != null) {
            order.setStartLikeCount(callback.getStartLikeCount());
        }
        if (callback.getStartFollowerCount() != null) {
            order.setStartFollowerCount(callback.getStartFollowerCount());
        }
        if (callback.getStartCommentCount() != null) {
            order.setStartCommentCount(callback.getStartCommentCount());
        }
        if (callback.getCurrentLikeCount() != null) {
            order.setCurrentLikeCount(callback.getCurrentLikeCount());
        }
        if (callback.getCurrentFollowerCount() != null) {
            order.setCurrentFollowerCount(callback.getCurrentFollowerCount());
        }
        if (callback.getCurrentCommentCount() != null) {
            order.setCurrentCommentCount(callback.getCurrentCommentCount());
        }

        int completed = callback.getCompleted() != null ? callback.getCompleted() : 0;

        if (completed > 0) {
            // Some actions completed - partial
            order.setStatus(OrderStatus.PARTIAL);
            order.setTrafficStatus("PARTIAL_FAILED");
            order.setViewsDelivered(completed);
            order.setRemains(order.getQuantity() - completed);
        } else {
            // Complete failure
            order.setStatus(OrderStatus.CANCELLED);
            order.setTrafficStatus("FAILED");
            order.setViewsDelivered(0);
            order.setRemains(order.getQuantity());
        }

        order.setErrorMessage("Instagram order failed");
        order.setLastErrorType("BOT_FAILURE");
        orderRepository.save(order);

        // Clear cache
        String cacheKey = INSTAGRAM_BOT_ORDER_ID_PREFIX + order.getId();
        redisTemplate.delete(cacheKey);

        // Send Telegram notification
        telegramNotificationService.notifyOrderFailed(order, completed);
    }

    /** Determine start count from callback. */
    private Integer determineStartCount(Order order, InstagramWebhookCallback callback) {
        Service service = order.getService();
        if (service == null || service.getCategory() == null) {
            return null;
        }

        String category = service.getCategory().toUpperCase();

        if (category.contains("LIKE")) {
            return callback.getStartLikeCount();
        } else if (category.contains("FOLLOW")) {
            return callback.getStartFollowerCount();
        } else if (category.contains("COMMENT")) {
            return callback.getStartCommentCount();
        }

        return null;
    }

    /** Get order status from bot. */
    public InstagramOrderStatus getOrderStatus(Long orderId) {
        String cacheKey = INSTAGRAM_BOT_ORDER_ID_PREFIX + orderId;
        Object botOrderId = redisTemplate.opsForValue().get(cacheKey);

        if (botOrderId == null) {
            log.warn("No bot order ID found for panel order {}", orderId);
            return null;
        }

        return botClient.getOrderStatus(botOrderId.toString());
    }

    /** Cancel an Instagram order. */
    @Transactional
    public boolean cancelOrder(Long orderId) {
        String cacheKey = INSTAGRAM_BOT_ORDER_ID_PREFIX + orderId;
        Object botOrderId = redisTemplate.opsForValue().get(cacheKey);

        if (botOrderId == null) {
            log.warn("No bot order ID found for panel order {}", orderId);
            return false;
        }

        boolean cancelled = botClient.cancelOrder(botOrderId.toString());

        if (cancelled) {
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isPresent()) {
                Order order = orderOpt.get();
                order.setStatus(OrderStatus.CANCELLED);
                order.setTrafficStatus("CANCELLED");
                orderRepository.save(order);
            }
            redisTemplate.delete(cacheKey);
        }

        return cancelled;
    }

    /** Check bot health status. */
    public InstagramHealthResponse checkBotHealth() {
        return botClient.checkHealth();
    }

    /** Check if bot is ready. */
    public boolean isBotReady() {
        return botClient.isReady();
    }

    /** Get bot queue statistics. */
    public Map<String, Object> getBotQueueStats() {
        return botClient.getQueueStats();
    }

    /** Control bot workers. */
    public boolean controlBotWorkers(String action) {
        return botClient.controlWorkers(action);
    }

    /**
     * Determine the AdsPower profile group based on service geo-targeting.
     *
     * @param service The service
     * @return Profile group name (e.g., "Success_KR", "Success_DE")
     */
    private String determineProfileGroup(Service service) {
        String geo = service.getGeoTargeting();
        if (geo == null || geo.isEmpty()) {
            return "Success"; // Default
        }

        return switch (geo.toUpperCase()) {
            case "KR", "KOREA" -> "Success_KR";
            case "DE", "GERMANY" -> "Success_DE";
            default -> "Success";
        };
    }
}
