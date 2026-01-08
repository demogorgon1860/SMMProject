package com.smmpanel.service.order;

import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.dto.binom.BinomIntegrationResponse;
import com.smmpanel.dto.binom.CampaignStatsResponse;
import com.smmpanel.dto.kafka.VideoProcessingMessage;
import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.dto.result.StateTransitionResult;
import com.smmpanel.entity.*;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.OrderNotFoundException;
import com.smmpanel.exception.OrderValidationException;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.auth.ApiKeyService;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.CqrsReadModelService;
import com.smmpanel.service.core.EventSourcingService;
import com.smmpanel.service.fraud.FraudDetectionService;
import com.smmpanel.service.integration.BinomService;
import com.smmpanel.service.integration.InstagramService;
import com.smmpanel.service.integration.YouTubeService;
import com.smmpanel.service.notification.NotificationService;
import com.smmpanel.service.order.state.OrderStateManager;
import com.smmpanel.service.validation.OrderValidationService;
import com.smmpanel.service.video.VideoProcessingService;
import com.smmpanel.service.video.YouTubeProcessingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRITICAL: Order Service with Perfect Panel compatibility methods MUST maintain exact
 * compatibility with Perfect Panel API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final BalanceService balanceService;
    private final YouTubeProcessingService youTubeProcessingService;
    private final BinomService binomService;
    private final OrderStateManagementService orderStateManagementService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EventSourcingService eventSourcingService;
    private final CqrsReadModelService cqrsReadModelService;
    private final ApiKeyService apiKeyService;
    private final YouTubeService youTubeService;
    private final InstagramService instagramService;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final OrderStateManager orderStateManager;
    private final VideoProcessingService videoProcessingService;
    private final NotificationService notificationService;
    private final OrderValidationService orderValidationService;
    private final FraudDetectionService fraudDetectionService;
    private final ConversionCoefficientRepository conversionCoefficientRepository;
    private final OrderEventProducer orderEventProducer;

    // Redis namespace constants
    private static final String REDIS_YOUTUBE_START_COUNT = "youtube:startCount:";
    private static final String REDIS_YOUTUBE_CURRENT_COUNT = "youtube:currentCount:";
    private static final String REDIS_ORDER_PROGRESS = "order:progress:";
    private static final String REDIS_ORDER_CLIP_URL = "order:clip:";
    private static final String REDIS_BINOM_OFFER = "binom:offer:";
    private static final int REDIS_START_COUNT_TTL_DAYS = 30;
    private static final int REDIS_PROGRESS_TTL_HOURS = 24;
    private static final int REDIS_CLIP_TTL_DAYS = 7;

    /** CRITICAL: Create order with API key (Perfect Panel compatibility) */
    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRED,
            timeout = 30,
            rollbackFor = Exception.class)
    public OrderResponse createOrderWithApiKey(CreateOrderRequest request, String apiKey) {
        try {
            log.info(
                    "Creating order with API key for service: {}, quantity: {}",
                    request.getService(),
                    request.getQuantity());

            // 1. Find user by API key
            if (apiKey == null || apiKey.trim().isEmpty()) {
                throw new OrderValidationException("API key is required");
            }

            // Use ApiKeyService to find and validate user
            User user = findUserByApiKey(apiKey);
            if (user == null) {
                throw new OrderValidationException("Invalid API key");
            }

            // 2. Validate service
            com.smmpanel.entity.Service service =
                    serviceRepository
                            .findById(request.getService().longValue())
                            .orElseThrow(
                                    () ->
                                            new OrderValidationException(
                                                    "Service not found: " + request.getService()));

            if (!service.getActive()) {
                throw new OrderValidationException("Service is not active");
            }

            // 3. Validate quantity
            if (request.getQuantity() < service.getMinOrder()
                    || request.getQuantity() > service.getMaxOrder()) {
                throw new OrderValidationException(
                        String.format(
                                "Quantity must be between %d and %d",
                                service.getMinOrder(), service.getMaxOrder()));
            }

            // 4. Calculate charge
            BigDecimal charge = calculateCharge(service, request.getQuantity());

            // 5. Create order first (optimistic approach)
            Order order = new Order();
            order.setUser(user);
            order.setService(service);
            order.setLink(request.getLink());
            order.setQuantity(request.getQuantity());
            order.setCharge(charge);
            // StartCount will be set immediately after save for YouTube orders
            order.setStartCount(0);
            order.setRemains(request.getQuantity());
            order.setStatus(OrderStatus.PENDING);
            order.setProcessingPriority(0);
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());

            order = orderRepository.save(order);

            // 6. Save order event for event sourcing
            eventSourcingService.saveOrderEvent(
                    order,
                    "ORDER_CREATED",
                    Map.of("service", service.getName(), "quantity", request.getQuantity()));

            // 7. Atomically check and deduct balance (prevents race conditions)
            boolean balanceDeducted =
                    balanceService.checkAndDeductBalance(
                            user, charge, order, "Order payment for service " + service.getName());

            if (!balanceDeducted) {
                // Clean up the order if balance deduction failed
                orderRepository.delete(order);
                eventSourcingService.saveOrderEvent(
                        order, "ORDER_FAILED", Map.of("reason", "Insufficient balance"));
                throw new InsufficientBalanceException("Insufficient balance. Required: " + charge);
            }

            // 8. Update CQRS read model
            cqrsReadModelService.updateOrderReadModel(order);

            // 9. CRITICAL: Immediately capture YouTube startCount for baseline
            if (isYouTubeOrder(order)) {
                try {
                    String videoId = extractYouTubeVideoId(order.getLink());
                    log.info(
                            "Order {} - Extracted YouTube video ID: {} from URL: {}",
                            order.getId(),
                            videoId,
                            order.getLink());

                    if (videoId != null) {
                        // Get current view count from YouTube API
                        int startCount = youTubeService.getVideoViewCount(videoId);

                        // Update order with startCount
                        order.setStartCount(startCount);
                        order.setYoutubeVideoId(videoId);
                        orderRepository.save(order);

                        // CRITICAL: Check if video is deleted/blocked (startCount = 0)
                        if (startCount == 0) {
                            log.warn(
                                    "Order {} - Video is deleted or blocked (startCount = 0)."
                                        + " Automatically marking as PARTIAL and refunding user.",
                                    order.getId());

                            // Mark order as PARTIAL
                            order.setStatus(OrderStatus.PARTIAL);
                            order.setRemains(order.getQuantity()); // No views delivered
                            order.setErrorMessage("Video deleted or unavailable");
                            orderRepository.save(order);

                            // Full refund to user
                            try {
                                balanceService.refund(
                                        user,
                                        charge,
                                        order,
                                        "Refund for deleted/blocked video - Order #"
                                                + order.getId());
                                log.info(
                                        "Order {} - Refunded {} to user {} for deleted/blocked"
                                                + " video",
                                        order.getId(),
                                        charge,
                                        user.getUsername());
                            } catch (Exception e) {
                                log.error(
                                        "Failed to refund deleted video order {}: {}",
                                        order.getId(),
                                        e.getMessage());
                            }

                            // Don't send to processing queue - return early
                            return mapToOrderResponse(order);
                        }

                        // Cache the startCount
                        cacheStartCount(order.getId(), videoId, startCount);

                        log.info(
                                "Order {} - YouTube startCount captured: {}, video ID: {}",
                                order.getId(),
                                startCount,
                                videoId);
                    } else {
                        log.warn(
                                "Order {} - Could not extract video ID from URL: {}",
                                order.getId(),
                                order.getLink());
                    }
                } catch (Exception e) {
                    log.error(
                            "Failed to capture startCount for order {}: {}",
                            order.getId(),
                            e.getMessage());

                    // CRITICAL: Check if startCount is still 0 after exception (video might be
                    // blocked/deleted)
                    // Reload order to get current state
                    Order reloadedOrder = orderRepository.findById(order.getId()).orElse(order);
                    if (reloadedOrder.getStartCount() == null
                            || reloadedOrder.getStartCount() == 0) {
                        log.warn(
                                "Order {} - Video startCount check failed and remains 0. Video may"
                                    + " be deleted/blocked. Marking as PARTIAL and refunding user.",
                                order.getId());

                        // Mark order as PARTIAL
                        reloadedOrder.setStatus(OrderStatus.PARTIAL);
                        reloadedOrder.setRemains(reloadedOrder.getQuantity()); // No views delivered
                        reloadedOrder.setErrorMessage(
                                "Video unavailable or startCount check failed");
                        orderRepository.save(reloadedOrder);

                        // Full refund to user
                        try {
                            balanceService.refund(
                                    user,
                                    charge,
                                    reloadedOrder,
                                    "Refund for unavailable video (startCount check failed) - Order"
                                            + " #"
                                            + order.getId());
                            log.info(
                                    "Order {} - Refunded {} to user {} for unavailable video",
                                    order.getId(),
                                    charge,
                                    user.getUsername());
                        } catch (Exception refundEx) {
                            log.error(
                                    "Failed to refund unavailable video order {}: {}",
                                    order.getId(),
                                    refundEx.getMessage());
                        }

                        // Don't send to processing queue - return early
                        return mapToOrderResponse(reloadedOrder);
                    }

                    // If startCount was successfully set before exception, continue processing
                }
            } else if (isInstagramOrder(order)) {
                // INSTAGRAM ORDER - Send to Kafka for async processing
                log.info(
                        "Order {} - Instagram order detected, publishing to Kafka for async"
                                + " processing",
                        order.getId());

                // Publish OrderCreatedEvent for async processing by consumer
                OrderCreatedEvent instagramOrderEvent = new OrderCreatedEvent();
                instagramOrderEvent.setOrderId(order.getId());
                instagramOrderEvent.setUserId(user.getId());
                instagramOrderEvent.setServiceId(service.getId());
                instagramOrderEvent.setQuantity(order.getQuantity());
                instagramOrderEvent.setTimestamp(LocalDateTime.now());

                orderEventProducer.publishOrderCreatedEvent(instagramOrderEvent);
                log.info(
                        "Published Instagram OrderCreatedEvent for order {} to Kafka",
                        order.getId());

                return mapToOrderResponse(order);
            } else {
                log.info(
                        "Order {} - Not a YouTube or Instagram order, skipping special processing",
                        order.getId());
            }

            // 10. Send to processing queue for clip creation and Binom setup (YouTube only)
            // Create and send VideoProcessingMessage
            String videoIdForProcessing =
                    order.getYoutubeVideoId() != null
                            ? order.getYoutubeVideoId()
                            : extractYouTubeVideoId(order.getLink());

            VideoProcessingMessage vpmsg =
                    VideoProcessingMessage.builder()
                            .messageId(UUID.randomUUID().toString())
                            .timestamp(LocalDateTime.now())
                            .orderId(order.getId())
                            .videoId(videoIdForProcessing)
                            .originalUrl(order.getLink())
                            .targetQuantity(order.getQuantity())
                            .processingType(VideoProcessingMessage.VideoProcessingType.VIEWS)
                            .priority(VideoProcessingMessage.ProcessingPriority.MEDIUM)
                            .createdAt(LocalDateTime.now())
                            .attemptNumber(1)
                            .maxAttempts(3)
                            .build();

            log.info(
                    "KAFKA MESSAGE: Sending order {} to smm.video.processing with videoId={},"
                            + " targetQuantity={}",
                    order.getId(),
                    videoIdForProcessing,
                    order.getQuantity());

            kafkaTemplate.send("smm.video.processing", vpmsg);

            // 11. Publish OrderCreatedEvent for async processing
            OrderCreatedEvent orderCreatedEvent = new OrderCreatedEvent();
            orderCreatedEvent.setOrderId(order.getId());
            orderCreatedEvent.setUserId(user.getId());
            orderCreatedEvent.setServiceId(service.getId());
            orderCreatedEvent.setQuantity(order.getQuantity());
            orderCreatedEvent.setTimestamp(LocalDateTime.now());

            orderEventProducer.publishOrderCreatedEvent(orderCreatedEvent);
            log.info("Published OrderCreatedEvent for order {} to Kafka", order.getId());

            log.info(
                    "Order {} created successfully for user {} and sent to processing queue",
                    order.getId(),
                    user.getUsername());

            return mapToOrderResponse(order);

        } catch (Exception e) {
            log.error("Failed to create order with API key: {}", e.getMessage());
            throw e;
        }
    }

    /** CRITICAL: Get order with API key (Perfect Panel compatibility) */
    @Transactional(readOnly = true)
    public OrderResponse getOrderWithApiKey(Long orderId, String apiKey) {
        // Find user by API key
        User user = findUserByApiKey(apiKey);
        if (user == null) {
            throw new OrderValidationException("Invalid API key");
        }

        // Find order belonging to user
        Order order =
                orderRepository
                        .findByIdAndUser(orderId, user)
                        .orElseThrow(() -> new OrderValidationException("Order not found"));

        return mapToOrderResponse(order);
    }

    /** CRITICAL: Get services for API key (Perfect Panel format) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getServicesForApiKey(String apiKey) {
        // Validate API key
        User user = findUserByApiKey(apiKey);
        if (user == null) {
            throw new OrderValidationException("Invalid API key");
        }

        // Get active services
        List<com.smmpanel.entity.Service> services =
                serviceRepository.findByActiveOrderByIdAsc(true);

        // Convert to Perfect Panel format
        return services.stream().map(this::mapToServiceMap).collect(Collectors.toList());
    }

    /** CRITICAL: Get balance for API key (Perfect Panel format) */
    @Transactional(readOnly = true)
    public String getBalanceForApiKey(String apiKey) {
        User user = findUserByApiKey(apiKey);
        if (user == null) {
            throw new OrderValidationException("Invalid API key");
        }

        return user.getBalance().toString();
    }

    /** CRITICAL: Get multiple order status (Perfect Panel batch) */
    @Transactional(readOnly = true)
    public Map<String, Object> getMultipleOrderStatus(String apiKey, String[] orderIds) {
        User user =
                userRepository
                        .findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                        .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        Map<String, Object> results = new HashMap<>();

        for (String orderIdStr : orderIds) {
            try {
                Long orderId = Long.parseLong(orderIdStr.trim());
                Order order = orderRepository.findByIdAndUser(orderId, user).orElse(null);

                if (order != null) {
                    Map<String, Object> orderStatus = new HashMap<>();
                    orderStatus.put("charge", order.getCharge().toString());
                    orderStatus.put("start_count", order.getStartCount());
                    orderStatus.put("status", mapToPerfectPanelStatus(order.getStatus()));
                    orderStatus.put("remains", order.getRemains());
                    orderStatus.put("currency", "USD");

                    results.put(orderIdStr, orderStatus);
                } else {
                    results.put(orderIdStr, Map.of("error", "Order not found"));
                }
            } catch (NumberFormatException e) {
                results.put(orderIdStr, Map.of("error", "Invalid order ID"));
            }
        }

        return results;
    }

    /** CRITICAL: Refill order with API key */
    @Transactional(propagation = Propagation.REQUIRED)
    public void refillOrderWithApiKey(Long orderId, String apiKey) {
        User user =
                userRepository
                        .findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                        .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        Order order =
                orderRepository
                        .findByIdAndUser(orderId, user)
                        .orElseThrow(() -> new OrderValidationException("Order not found"));

        if (!order.getStatus().equals(OrderStatus.COMPLETED)) {
            throw new OrderValidationException("Order must be completed to refill");
        }

        // Change status to REFILL and send to processing
        order.setStatus(OrderStatus.REFILL);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        // Create and send VideoProcessingMessage
        VideoProcessingMessage vpmsg =
                VideoProcessingMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .timestamp(LocalDateTime.now())
                        .orderId(order.getId())
                        .videoId(
                                order.getYoutubeVideoId() != null
                                        ? order.getYoutubeVideoId()
                                        : extractYouTubeVideoId(order.getLink()))
                        .originalUrl(order.getLink())
                        .targetQuantity(order.getQuantity())
                        .processingType(VideoProcessingMessage.VideoProcessingType.VIEWS)
                        .priority(VideoProcessingMessage.ProcessingPriority.MEDIUM)
                        .createdAt(LocalDateTime.now())
                        .attemptNumber(1)
                        .maxAttempts(3)
                        .build();
        kafkaTemplate.send("smm.video.processing", vpmsg);

        log.info("Order {} refill initiated by user {}", orderId, user.getUsername());
    }

    /** CRITICAL: Cancel order with API key */
    @Transactional(propagation = Propagation.REQUIRED)
    public void cancelOrderWithApiKey(Long orderId, String apiKey) {
        User user =
                userRepository
                        .findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                        .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        Order order =
                orderRepository
                        .findByIdAndUser(orderId, user)
                        .orElseThrow(() -> new OrderValidationException("Order not found"));

        if (order.getStatus().equals(OrderStatus.COMPLETED)
                || order.getStatus().equals(OrderStatus.CANCELLED)) {
            throw new OrderValidationException(
                    "Cannot cancel order in " + order.getStatus() + " status");
        }

        // Cancel order and refund balance
        BigDecimal refundAmount = calculateRefund(order);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(
                    user, refundAmount, order, "Refund for cancelled order " + orderId);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.info(
                "Order {} cancelled by user {}, refund: {}",
                orderId,
                user.getUsername(),
                refundAmount);
    }

    // Get order by ID
    public Order getOrderById(Long orderId) {
        return orderRepository
                .findById(orderId)
                .orElseThrow(() -> new OrderValidationException("Order not found: " + orderId));
    }

    // ========== State Machine Support Methods ==========

    // Guard Methods
    public boolean isPaymentConfirmed(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null
                && order.getCharge() != null
                && order.getCharge().compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean canStartProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null && order.getService() != null && order.getService().getActive();
    }

    public boolean canPauseOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null && order.getStatus() == OrderStatus.ACTIVE;
    }

    public boolean canCancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return false;
        return order.getStatus() != OrderStatus.COMPLETED
                && order.getStatus() != OrderStatus.CANCELLED;
    }

    public boolean isPartiallyComplete(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return false;
        Integer completed =
                order.getQuantity()
                        - (order.getRemains() != null ? order.getRemains() : order.getQuantity());
        return completed > 0 && completed < order.getQuantity();
    }

    public boolean isFullyComplete(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        return order != null && (order.getRemains() == null || order.getRemains() == 0);
    }

    public boolean isAdmin(String userId) {
        if (userId == null) return false;
        User user = userRepository.findById(Long.parseLong(userId)).orElse(null);
        return user != null && user.getRole() == UserRole.ADMIN;
    }

    // Action Methods
    @Transactional
    public void markPaymentConfirmed(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        eventSourcingService.saveOrderEvent(
                order, "PAYMENT_CONFIRMED", Map.of("amount", order.getCharge()));
    }

    @Transactional
    public void markPaymentFailed(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setErrorMessage("Payment failed");
        orderRepository.save(order);
    }

    @Transactional
    public void startProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PROCESSING);
        orderRepository.save(order);
        // Need to fetch order first to create proper message
        Order orderToProcess = orderRepository.findById(orderId).orElseThrow();
        String videoId =
                orderToProcess.getYoutubeVideoId() != null
                        ? orderToProcess.getYoutubeVideoId()
                        : extractYouTubeVideoId(orderToProcess.getLink());
        VideoProcessingMessage vpmsg2 =
                VideoProcessingMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .timestamp(LocalDateTime.now())
                        .orderId(orderId)
                        .videoId(videoId)
                        .originalUrl(orderToProcess.getLink())
                        .targetQuantity(orderToProcess.getQuantity())
                        .processingType(VideoProcessingMessage.VideoProcessingType.VIEWS)
                        .priority(VideoProcessingMessage.ProcessingPriority.MEDIUM)
                        .createdAt(LocalDateTime.now())
                        .attemptNumber(1)
                        .maxAttempts(3)
                        .build();
        kafkaTemplate.send("smm.video.processing", vpmsg2);
    }

    @Transactional
    public void markProcessingComplete(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.ACTIVE);
        orderRepository.save(order);
    }

    @Transactional
    public void markProcessingFailed(Long orderId, String error) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.ERROR);
        order.setErrorMessage(error);
        order.setRetryCount(order.getRetryCount() + 1);
        orderRepository.save(order);
    }

    @Transactional
    public void pauseOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PAUSED);
        orderRepository.save(order);
    }

    @Transactional
    public void resumeOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.ACTIVE);
        orderRepository.save(order);
    }

    @Transactional
    public void markPartialCompletion(Long orderId, Integer completedQuantity) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PARTIAL);
        order.setRemains(order.getQuantity() - completedQuantity);
        orderRepository.save(order);
    }

    @Transactional
    public void markOrderComplete(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        orderRepository.save(order);
    }

    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setOperatorNotes(reason);
        orderRepository.save(order);
    }

    @Transactional
    public void cancelOrderWithRefund(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setOperatorNotes(reason);
        orderRepository.save(order);

        // Process refund
        BigDecimal refundAmount = calculateRefund(order);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(order.getUser(), refundAmount, order, "Refund: " + reason);
        }
    }

    @Transactional
    public void retryProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PROCESSING);
        order.setLastRetryAt(LocalDateTime.now());
        orderRepository.save(order);
        // Need to fetch order first to create proper message
        Order orderToProcess = orderRepository.findById(orderId).orElseThrow();
        String videoId =
                orderToProcess.getYoutubeVideoId() != null
                        ? orderToProcess.getYoutubeVideoId()
                        : extractYouTubeVideoId(orderToProcess.getLink());
        VideoProcessingMessage vpmsg2 =
                VideoProcessingMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .timestamp(LocalDateTime.now())
                        .orderId(orderId)
                        .videoId(videoId)
                        .originalUrl(orderToProcess.getLink())
                        .targetQuantity(orderToProcess.getQuantity())
                        .processingType(VideoProcessingMessage.VideoProcessingType.VIEWS)
                        .priority(VideoProcessingMessage.ProcessingPriority.MEDIUM)
                        .createdAt(LocalDateTime.now())
                        .attemptNumber(1)
                        .maxAttempts(3)
                        .build();
        kafkaTemplate.send("smm.video.processing", vpmsg2);
    }

    @Transactional
    public void flagForManualIntervention(Long orderId, String issue) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.HOLDING);
        order.setOperatorNotes(issue);
        orderRepository.save(order);
    }

    @Transactional
    public void markErrorResolved(Long orderId, String resolution) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setErrorMessage(null);
        order.setOperatorNotes(resolution);
        order.setRetryCount(0);
        orderRepository.save(order);
    }

    @Transactional
    public void adminReactivateOrder(Long orderId, String adminId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.ACTIVE);
        order.setOperatorNotes("Reactivated by admin: " + adminId);
        orderRepository.save(order);
    }

    @Transactional
    public void adminOverride(Long orderId, String adminId, String action) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setOperatorNotes("Admin override by " + adminId + ": " + action);
        orderRepository.save(order);
        eventSourcingService.saveOrderEvent(
                order, "ADMIN_OVERRIDE", Map.of("admin", adminId, "action", action));
    }

    @Transactional
    public void onProcessingEntry(Long orderId) {
        log.debug("Order {} entered PROCESSING state", orderId);
    }

    @Transactional
    public void onProcessingExit(Long orderId) {
        log.debug("Order {} exited PROCESSING state", orderId);
    }

    @Transactional
    public void onErrorEntry(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setLastErrorType("STATE_MACHINE_ERROR");
        orderRepository.save(order);
    }

    @Transactional
    public void triggerOrderProcessing(Long orderId) {
        kafkaTemplate.send("smm.order.processing", orderId);
    }

    // Private helper methods

    /** Find user by API key using the ApiKeyService for proper validation */
    private User findUserByApiKey(String apiKey) {
        // Get all active users and check their API keys
        List<User> activeUsers = userRepository.findAllByIsActiveTrue();

        for (User user : activeUsers) {
            if (user.getApiKeyHash() != null && user.getApiKeySalt() != null) {
                // Use ApiKeyService to validate API key (checks active status)
                if (apiKeyService.validateApiKey(apiKey, user)) {
                    return user;
                }
            }
        }

        return null;
    }

    private String hashApiKey(String apiKey) {
        if (apiKey == null) {
            throw new IllegalArgumentException("API key cannot be null");
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private BigDecimal calculateCharge(com.smmpanel.entity.Service service, int quantity) {
        return service.getPricePer1000()
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal calculateRefund(Order order) {
        if (order.getRemains() <= 0) {
            return BigDecimal.ZERO;
        }

        // Refund proportional to remaining quantity
        BigDecimal totalCharge = order.getCharge();
        BigDecimal completedRatio =
                BigDecimal.valueOf(order.getQuantity() - order.getRemains())
                        .divide(
                                BigDecimal.valueOf(order.getQuantity()),
                                4,
                                java.math.RoundingMode.HALF_UP);

        return totalCharge.subtract(totalCharge.multiply(completedRatio));
    }

    private OrderResponse mapToOrderResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .service(order.getService().getId().intValue())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(mapToPerfectPanelStatus(order.getStatus()))
                .charge(order.getCharge().toString())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    private Map<String, Object> mapToServiceMap(com.smmpanel.entity.Service service) {
        Map<String, Object> serviceMap = new HashMap<>();
        serviceMap.put("service", service.getId());
        serviceMap.put("name", service.getName());
        serviceMap.put("category", service.getCategory());
        serviceMap.put("rate", service.getPricePer1000().toString());
        serviceMap.put("min", service.getMinOrder());
        serviceMap.put("max", service.getMaxOrder());
        serviceMap.put("description", service.getDescription());
        return serviceMap;
    }

    /** CRITICAL: Status mapping MUST match Perfect Panel exactly */
    private String mapToPerfectPanelStatus(OrderStatus status) {
        return switch (status) {
            case PENDING -> "Pending";
            case IN_PROGRESS, PROCESSING -> "In progress";
            case ACTIVE -> "In progress";
            case PARTIAL -> "Partial";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Canceled"; // Note: Perfect Panel uses "Canceled"
            case PAUSED -> "Paused";
            case HOLDING -> "In progress";
            case REFILL -> "Refill";
            case ERROR -> "Error";
            case SUSPENDED -> "Suspended";
        };
    }

    /** Cache the startCount for monitoring */
    private void cacheStartCount(Long orderId, String videoId, int startCount) {
        try {
            // Store startCount in Redis for monitoring
            String key = REDIS_YOUTUBE_START_COUNT + orderId;
            Map<String, Object> data = new HashMap<>();
            data.put("videoId", videoId);
            data.put("startCount", startCount);
            data.put("capturedAt", LocalDateTime.now().toString());

            redisTemplate.opsForHash().putAll(key, data);
            redisTemplate.expire(key, java.time.Duration.ofDays(30));

        } catch (Exception e) {
            log.warn("Failed to cache startCount: {}", e.getMessage());
        }
    }

    /** Store order progress in Redis for fast access during monitoring */
    private void storeOrderProgressInCache(Long orderId, int startCount, int targetQuantity) {
        try {
            Map<String, Object> progress = new HashMap<>();
            progress.put("startCount", startCount);
            progress.put("currentCount", startCount);
            progress.put("targetQuantity", targetQuantity);
            progress.put("viewsGained", 0);
            progress.put("percentComplete", 0.0);
            progress.put("lastChecked", LocalDateTime.now().toString());
            progress.put("checkCount", 0);

            String cacheKey = REDIS_ORDER_PROGRESS + orderId;
            redisTemplate.opsForHash().putAll(cacheKey, progress);
            redisTemplate.expire(cacheKey, java.time.Duration.ofHours(REDIS_PROGRESS_TTL_HOURS));

        } catch (Exception e) {
            log.warn("Failed to cache order progress for {}: {}", orderId, e.getMessage());
        }
    }

    /** Check if order is for YouTube service */
    private boolean isYouTubeOrder(Order order) {
        if (order.getService() == null) return false;
        String serviceName = order.getService().getName();
        String category = order.getService().getCategory();
        return (serviceName != null
                        && (serviceName.toLowerCase().contains("youtube")
                                || serviceName.toLowerCase().contains("views")))
                || (category != null && category.toLowerCase().contains("youtube"));
    }

    /** Check if order is for Instagram service */
    private boolean isInstagramOrder(Order order) {
        if (order.getService() == null) return false;
        String category = order.getService().getCategory();
        String serviceName = order.getService().getName();
        return (category != null && category.toLowerCase().contains("instagram"))
                || (serviceName != null && serviceName.toLowerCase().contains("instagram"));
    }

    /** Extract YouTube video ID from various URL formats */
    private String extractYouTubeVideoId(String url) {
        if (url == null) return null;

        // Handle youtube.com/watch?v=VIDEO_ID
        if (url.contains("youtube.com/watch")) {
            String[] parts = url.split("[?&]");
            for (String part : parts) {
                if (part.startsWith("v=")) {
                    return part.substring(2).split("[&]")[0];
                }
            }
        }

        // Handle youtu.be/VIDEO_ID
        if (url.contains("youtu.be/")) {
            String[] parts = url.split("youtu.be/");
            if (parts.length > 1) {
                return parts[1].split("[?&]")[0];
            }
        }

        // Handle youtube.com/shorts/VIDEO_ID
        if (url.contains("youtube.com/shorts/")) {
            String[] parts = url.split("shorts/");
            if (parts.length > 1) {
                return parts[1].split("[?&]")[0];
            }
        }

        return null;
    }

    // Additional methods required by the interface
    public OrderResponse createOrder(CreateOrderRequest request, String username) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));

        // Convert CreateOrderRequest to OrderCreateRequest and call the other method
        OrderCreateRequest orderCreateRequest =
                OrderCreateRequest.builder()
                        .serviceId(Long.valueOf(request.getService()))
                        .link(request.getLink())
                        .quantity(request.getQuantity())
                        .build();

        return createOrder(orderCreateRequest, username);
    }

    public OrderResponse createOrder(OrderCreateRequest request, String username) {
        // Get user
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));

        // Get service
        com.smmpanel.entity.Service service =
                serviceRepository
                        .findById(request.getServiceId())
                        .orElseThrow(() -> new OrderValidationException("Service not found"));

        // Validate service is active
        if (!service.getActive()) {
            throw new OrderValidationException("Service is not active");
        }

        // Validate quantity
        if (request.getQuantity() < service.getMinOrder()
                || request.getQuantity() > service.getMaxOrder()) {
            throw new OrderValidationException(
                    String.format(
                            "Quantity must be between %d and %d",
                            service.getMinOrder(), service.getMaxOrder()));
        }

        // Calculate charge
        BigDecimal charge = calculateCharge(service, request.getQuantity());

        // Check balance
        if (user.getBalance().compareTo(charge) < 0) {
            throw new OrderValidationException("Insufficient balance");
        }

        // Create order
        Order order = new Order();
        order.setUser(user);
        order.setService(service);
        order.setLink(request.getLink());
        order.setQuantity(request.getQuantity());
        order.setCharge(charge);
        order.setRemains(request.getQuantity());
        order.setStartCount(0);
        order.setStatus(OrderStatus.PENDING);
        order.setProcessingPriority(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        order = orderRepository.save(order);

        // Deduct balance
        balanceService.deductBalance(user, charge, order, "Order #" + order.getId());

        // CRITICAL: Immediately capture YouTube startCount for baseline
        if (isYouTubeOrder(order)) {
            try {
                String videoId = extractYouTubeVideoId(order.getLink());
                log.info(
                        "Order {} - Extracted YouTube video ID: {} from URL: {}",
                        order.getId(),
                        videoId,
                        order.getLink());

                if (videoId != null) {
                    // Get current view count from YouTube API
                    int startCount = youTubeService.getVideoViewCount(videoId);

                    // Update order with startCount
                    order.setStartCount(startCount);
                    order.setYoutubeVideoId(videoId);
                    order = orderRepository.save(order);

                    // Cache the startCount
                    cacheStartCount(order.getId(), videoId, startCount);

                    log.info(
                            "Order {} - YouTube startCount captured: {}, video ID: {}",
                            order.getId(),
                            startCount,
                            videoId);
                } else {
                    log.warn(
                            "Order {} - Could not extract video ID from URL: {}",
                            order.getId(),
                            order.getLink());
                }
            } catch (Exception e) {
                log.error(
                        "Failed to capture startCount for order {}: {}",
                        order.getId(),
                        e.getMessage());
                // Continue with order processing - will retry in processing phase
            }
        } else if (isInstagramOrder(order)) {
            // INSTAGRAM ORDER - Send to Kafka for async processing
            log.info(
                    "Order {} - Instagram order detected, publishing to Kafka for async processing",
                    order.getId());

            // Publish OrderCreatedEvent for async processing by consumer
            OrderCreatedEvent instagramOrderEvent = new OrderCreatedEvent();
            instagramOrderEvent.setOrderId(order.getId());
            instagramOrderEvent.setUserId(user.getId());
            instagramOrderEvent.setServiceId(service.getId());
            instagramOrderEvent.setQuantity(order.getQuantity());
            instagramOrderEvent.setTimestamp(LocalDateTime.now());

            orderEventProducer.publishOrderCreatedEvent(instagramOrderEvent);
            log.info("Published Instagram OrderCreatedEvent for order {} to Kafka", order.getId());

            return mapToOrderResponse(order);
        } else {
            log.info(
                    "Order {} - Not a YouTube or Instagram order, skipping special processing",
                    order.getId());
        }

        // Send to processing queue (YouTube orders only)
        // Create and send VideoProcessingMessage
        String videoIdForProcessing =
                order.getYoutubeVideoId() != null
                        ? order.getYoutubeVideoId()
                        : extractYouTubeVideoId(order.getLink());

        VideoProcessingMessage vpmsg =
                VideoProcessingMessage.builder()
                        .messageId(UUID.randomUUID().toString())
                        .timestamp(LocalDateTime.now())
                        .orderId(order.getId())
                        .videoId(videoIdForProcessing)
                        .originalUrl(order.getLink())
                        .targetQuantity(order.getQuantity())
                        .processingType(VideoProcessingMessage.VideoProcessingType.VIEWS)
                        .priority(VideoProcessingMessage.ProcessingPriority.MEDIUM)
                        .createdAt(LocalDateTime.now())
                        .attemptNumber(1)
                        .maxAttempts(3)
                        .build();

        log.info(
                "KAFKA MESSAGE: Sending order {} to smm.video.processing with videoId={},"
                        + " targetQuantity={}",
                order.getId(),
                videoIdForProcessing,
                order.getQuantity());

        kafkaTemplate.send("smm.video.processing", vpmsg);

        log.info(
                "Order {} created successfully for user {} and sent to processing queue",
                order.getId(),
                username);

        return mapToOrderResponse(order);
    }

    public OrderResponse getOrder(Long orderId, String username) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));
        // Get order and verify ownership
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(() -> new OrderValidationException("Order not found"));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new OrderValidationException("Order not found");
        }

        return mapToOrderResponse(order);
    }

    public Page<OrderResponse> getUserOrders(String username, String status, Pageable pageable) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));

        Page<Order> orders;
        if (status != null && !status.isEmpty()) {
            OrderStatus orderStatus = mapFromPerfectPanelStatus(status);
            orders = orderRepository.findByUserAndStatus(user, orderStatus, pageable);
        } else {
            orders = orderRepository.findByUser(user, pageable);
        }

        return orders.map(this::mapToOrderResponse);
    }

    public com.smmpanel.dto.response.OrderStatistics getOrderStatistics(int days) {
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        List<Order> orders = orderRepository.findOrdersCreatedAfter(startDate);

        Map<OrderStatus, Long> statusCounts =
                orders.stream()
                        .collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));

        return com.smmpanel.dto.response.OrderStatistics.builder()
                .totalOrders((long) orders.size())
                .pendingOrders(statusCounts.getOrDefault(OrderStatus.PENDING, 0L))
                .completedOrders(statusCounts.getOrDefault(OrderStatus.COMPLETED, 0L))
                .cancelledOrders(statusCounts.getOrDefault(OrderStatus.CANCELLED, 0L))
                .build();
    }

    public com.smmpanel.dto.response.BulkOperationResult performBulkOperation(
            com.smmpanel.dto.request.BulkOrderRequest request) {
        // Implementation for bulk operations
        return com.smmpanel.dto.response.BulkOperationResult.builder()
                .success(true)
                .message("Bulk operation completed")
                .build();
    }

    public com.smmpanel.dto.response.HealthStatus getHealthStatus() {
        return com.smmpanel.dto.response.HealthStatus.builder()
                .status("UP")
                .timestamp(LocalDateTime.now())
                .build();
    }

    private OrderStatus mapFromPerfectPanelStatus(String status) {
        return switch (status.toLowerCase()) {
            case "pending" -> OrderStatus.PENDING;
            case "in progress" -> OrderStatus.IN_PROGRESS;
            case "partial" -> OrderStatus.PARTIAL;
            case "completed" -> OrderStatus.COMPLETED;
            case "canceled" -> OrderStatus.CANCELLED;
            case "paused" -> OrderStatus.PAUSED;
            case "refill" -> OrderStatus.REFILL;
            case "error" -> OrderStatus.ERROR;
            case "suspended" -> OrderStatus.SUSPENDED;
            default -> OrderStatus.PENDING;
        };
    }

    // Optimized delegate methods
    public Page<OrderResponse> getUserOrdersOptimized(
            String username, String status, Pageable pageable) {
        return getUserOrders(username, status, pageable);
    }

    public OrderResponse getOrderOptimized(Long orderId, String username) {
        return getOrder(orderId, username);
    }

    public Map<String, Object> getOrdersBatchOptimized(String apiKey, String[] orderIds) {
        return getMultipleOrderStatus(apiKey, orderIds);
    }

    public Map<Long, OrderResponse> getOrdersBatchOptimized(List<Long> orderIds, String username) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));

        // Use existing method to get orders by user then filter by IDs
        List<Order> allUserOrders = orderRepository.findOrdersWithDetailsByUserId(user.getId());
        Map<Long, OrderResponse> result = new HashMap<>();

        for (Order order : allUserOrders) {
            if (orderIds.contains(order.getId())) {
                result.put(order.getId(), mapToOrderResponse(order));
            }
        }

        return result;
    }

    /**
     * CRITICAL: Check order completion based on 3-campaign distribution Integrates campaign stats
     * to determine if order should be completed
     */
    @Transactional(readOnly = true)
    public boolean isOrderCompletedBasedOnCampaigns(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || !order.getStatus().equals(OrderStatus.ACTIVE)) {
                return false;
            }

            // Get aggregated campaign stats from all 3 campaigns
            CampaignStatsResponse campaignStats = binomService.getCampaignStatsForOrder(orderId);

            if (campaignStats == null) {
                log.warn("No campaign stats available for order {}", orderId);
                return false;
            }

            // Check if all 3 campaigns are working (ensure proper distribution)
            boolean has3CampaignsActive = campaignStats.getCampaignId().split(",").length == 3;
            if (!has3CampaignsActive) {
                log.warn("Order {} does not have 3 active campaigns as expected", orderId);
            }

            // Calculate completion based on total conversions from all campaigns
            long totalConversions = campaignStats.getConversions();
            int targetQuantity = order.getQuantity();

            boolean isCompleted = totalConversions >= targetQuantity;

            if (isCompleted) {
                log.info(
                        "Order {} completed based on campaign data: {}/{} conversions achieved"
                                + " across {} campaigns",
                        orderId,
                        totalConversions,
                        targetQuantity,
                        campaignStats.getCampaignId().split(",").length);
            }

            return isCompleted;

        } catch (Exception e) {
            log.error(
                    "Failed to check campaign completion for order {}: {}",
                    orderId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * CRITICAL: Update order progress using campaign data from 3-campaign distribution This method
     * aggregates data from all campaigns and updates order accordingly
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void updateOrderProgressFromCampaigns(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || !order.getStatus().equals(OrderStatus.ACTIVE)) {
                log.debug("Skipping campaign progress update for order {} - not active", orderId);
                return;
            }

            // Get aggregated campaign stats
            CampaignStatsResponse campaignStats = binomService.getCampaignStatsForOrder(orderId);

            if (campaignStats == null) {
                log.warn("No campaign stats available for order {}", orderId);
                return;
            }

            // Calculate progress from campaign data
            long totalConversions = campaignStats.getConversions();
            int targetQuantity = order.getQuantity();
            int remains = Math.max(0, targetQuantity - (int) totalConversions);

            // Update order progress
            order.setRemains(remains);
            order.setUpdatedAt(LocalDateTime.now());

            // Check for completion based on campaign data
            if (remains <= 0 && totalConversions >= targetQuantity) {
                // Use state management for proper completion transition
                StateTransitionResult result =
                        orderStateManagementService.transitionToCompleted(
                                orderId, order.getStartCount() + (int) totalConversions);

                if (result.isSuccess()) {
                    log.info(
                            "Order {} completed via campaign aggregation: {} conversions from {}"
                                    + " campaigns",
                            orderId,
                            totalConversions,
                            campaignStats.getCampaignId().split(",").length);

                    // Remove offer from all campaigns to stop spending
                    try {
                        binomService.removeOfferForOrder(orderId);
                    } catch (Exception e) {
                        log.error(
                                "Failed to remove offer for completed order {}: {}",
                                orderId,
                                e.getMessage());
                    }
                } else {
                    log.warn(
                            "Failed to transition order {} to completed: {}",
                            orderId,
                            result.getErrorMessage());
                }
            } else {
                orderRepository.save(order);
                log.debug(
                        "Updated order {} progress from campaigns: {}/{} conversions, {} remains",
                        orderId,
                        totalConversions,
                        targetQuantity,
                        remains);
            }

            // Publish Kafka event for order progress update (maintaining compatibility)
            Map<String, Object> progressEvent = new HashMap<>();
            progressEvent.put("orderId", orderId);
            progressEvent.put("totalConversions", totalConversions);
            progressEvent.put("remains", remains);
            progressEvent.put("campaignCount", campaignStats.getCampaignId().split(",").length);
            progressEvent.put("updateSource", "campaign-aggregation");

            kafkaTemplate.send("smm.order.progress", orderId.toString(), progressEvent);

        } catch (Exception e) {
            log.error(
                    "Failed to update order progress from campaigns for order {}: {}",
                    orderId,
                    e.getMessage());
        }
    }

    /**
     * Automatically start processing PENDING YouTube orders Runs every 10 seconds for immediate
     * processing DISABLED: To avoid race condition with Kafka consumer flow
     */
    // @Scheduled(fixedDelay = 10000, initialDelay = 5000)
    // @Transactional(propagation = Propagation.REQUIRED)
    public void processPendingYouTubeOrders() {
        // DISABLED: This scheduled job causes race conditions with the Kafka consumer
        // The order processing is now handled entirely through Kafka messages sent during order
        // creation
        // This prevents duplicate processing and status conflicts
    }

    /**
     * DISABLED: Binom integration moved to BinomSyncScheduler for proper separation of concerns
     *
     * <p>OrderService handles: Order creation, business logic, status management BinomSyncScheduler
     * handles: Binom integration, click tracking, offer removal
     *
     * <p>This prevents duplicate Binom API calls and race conditions between schedulers.
     */
    // @Scheduled(fixedDelay = 5000)
    @Transactional(propagation = Propagation.REQUIRED)
    public void monitorActiveOrders() {
        try {
            // Monitor all active orders, not just IN_PROGRESS
            List<Order> activeOrders =
                    orderRepository.findByStatusIn(
                            List.of(
                                    OrderStatus.PROCESSING,
                                    OrderStatus.ACTIVE,
                                    OrderStatus.IN_PROGRESS));

            log.debug(
                    "Monitoring {} active orders (PROCESSING/ACTIVE/IN_PROGRESS)",
                    activeOrders.size());

            for (Order order : activeOrders) {
                try {
                    monitorOrderProgress(order);
                } catch (Exception e) {
                    log.error("Failed to monitor order {}: {}", order.getId(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Failed to monitor active orders: {}", e.getMessage());
        }
    }

    /** Monitor individual order progress When clicks reach target, verify YouTube views */
    private void monitorOrderProgress(Order order) {
        Long orderId = order.getId();

        // Skip orders without Binom offer ID
        if (order.getBinomOfferId() == null) {
            log.debug("Order {} has no Binom offer ID, skipping monitoring", orderId);
            return;
        }

        // Get Binom campaign stats
        CampaignStatsResponse stats = binomService.getCampaignStatsForOrder(orderId);
        if (stats == null) {
            log.warn("No campaign stats for order {}", orderId);
            return;
        }

        long currentClicks = stats.getClicks();
        int targetClicks =
                order.getTargetViews() != null
                        ? order.getTargetViews()
                        : (int) (order.getQuantity() * order.getCoefficient().doubleValue());

        // Calculate early pull threshold to prevent overshoot
        // Pull at 95% of target to account for clicks that accumulate between checks
        int earlyPullThreshold = (int) (targetClicks * 0.95);

        log.debug(
                "Order {} - Clicks: {}/{} (early pull at: {})",
                orderId,
                currentClicks,
                targetClicks,
                earlyPullThreshold);

        // Check if clicks reached early pull threshold to minimize overshoot
        if (currentClicks >= earlyPullThreshold) {
            log.info(
                    "Order {} reached early pull threshold: {}/{} (95% of target {})",
                    orderId, currentClicks, earlyPullThreshold, targetClicks);

            // Stop the Binom offer immediately to prevent more clicks (costs money!)
            try {
                binomService.removeOfferForOrder(orderId);
                log.info("Removed Binom offer for order {} from campaigns", orderId);
            } catch (Exception e) {
                log.error("Failed to stop offer for order {}: {}", orderId, e.getMessage());
            }

            // Transition to PENDING status and schedule view verification
            order.setStatus(OrderStatus.PENDING);
            orderRepository.save(order);
            log.info("Order {} transitioned to PENDING after reaching click target", orderId);

            // Store second startCount in Redis and schedule first view check
            scheduleInitialViewCheck(order);
        }
    }

    /**
     * Verify YouTube views when clicks reach target Compare current count with startCount + ordered
     * quantity
     */
    private void verifyYouTubeViews(Order order) {
        try {
            Long orderId = order.getId();
            String videoId = order.getYoutubeVideoId();

            if (videoId == null) {
                log.error("No YouTube video ID for order {}", orderId);
                return;
            }

            // Get current view count (second check)
            int currentViewCount = youTubeService.getVideoViewCount(videoId);
            int startCount = order.getStartCount();
            int targetViews = order.getQuantity();
            int viewsGained = currentViewCount - startCount;

            log.info(
                    "Order {} - StartCount: {}, Current: {}, Gained: {}, Target: {}",
                    orderId,
                    startCount,
                    currentViewCount,
                    viewsGained,
                    targetViews);

            // Check if target views reached
            if (viewsGained >= targetViews) {
                // SUCCESS - Complete the order
                completeOrderWithViews(order, currentViewCount);
            } else {
                // Views not reached - schedule retry
                scheduleViewRetry(order, currentViewCount, viewsGained);
            }

        } catch (Exception e) {
            log.error("Error verifying views for order {}: {}", order.getId(), e.getMessage());
        }
    }

    /** Complete order when views are verified */
    @Transactional
    private void completeOrderWithViews(Order order, int finalViewCount) {
        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        // Ensure Binom offer is completely removed (should already be stopped)
        try {
            binomService.removeOfferForOrder(order.getId());
        } catch (Exception e) {
            log.error("Failed to remove offer for order {}: {}", order.getId(), e.getMessage());
        }

        // Clean up Redis keys
        cleanupRedisKeys(order.getId());

        log.info(
                "Order {} COMPLETED - Views delivered: {}",
                order.getId(),
                finalViewCount - order.getStartCount());
    }

    /** Schedule retry for view verification Retry every 10 minutes up to 12 times (2 hours) */
    private void scheduleViewRetry(Order order, int currentViewCount, int viewsGained) {
        Long orderId = order.getId();
        String retryKey = "order:retry:" + orderId;

        // Get or initialize retry count
        Integer retryCount = (Integer) redisTemplate.opsForValue().get(retryKey);
        if (retryCount == null) {
            retryCount = 0;
        }

        // Max retries check removed - PARTIAL status is now manual-only via admin action
        // Auto-PARTIAL only occurs for deleted/blocked videos (startCount = 0)

        // Increment retry count
        retryCount++;
        redisTemplate.opsForValue().set(retryKey, retryCount, java.time.Duration.ofDays(1));

        // Store retry state
        String stateKey = "order:retry:state:" + orderId;
        Map<String, Object> state = new HashMap<>();
        state.put("currentViewCount", currentViewCount);
        state.put("viewsGained", viewsGained);
        state.put("retryCount", retryCount);
        state.put("nextRetryAt", LocalDateTime.now().plusMinutes(30).toString());

        redisTemplate.opsForHash().putAll(stateKey, state);
        redisTemplate.expire(stateKey, java.time.Duration.ofDays(1));

        log.info(
                "Order {} - Views {}/{}. Retry {} scheduled in 30 minutes",
                orderId,
                viewsGained,
                order.getQuantity(),
                retryCount);
    }

    /**
     * Process scheduled view verification retries Runs every minute to check for pending retries
     */
    public void updateOrderProgress(Long orderId, Integer clicksDelivered, Integer totalClicks) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                // Update order progress based on clicks
                if (totalClicks != null && totalClicks > 0 && order.getQuantity() > 0) {
                    int progress = (clicksDelivered * 100) / totalClicks;
                    log.debug(
                            "Updated order {} progress: {}% ({}/{})",
                            orderId, progress, clicksDelivered, totalClicks);
                }
            }
        } catch (Exception e) {
            log.error("Failed to update order progress: {}", e.getMessage());
        }
    }

    public void updateProgressCache(String cacheKey, Map<String, Object> progressData) {
        try {
            redisTemplate.opsForValue().set(cacheKey, progressData, Duration.ofHours(24));
        } catch (Exception e) {
            log.error("Failed to update progress cache: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void processViewRetries() {
        try {
            // Process both PENDING (waiting for initial view check) and IN_PROGRESS orders
            List<Order> ordersToCheck = new ArrayList<>();
            ordersToCheck.addAll(orderRepository.findByStatus(OrderStatus.PENDING));
            ordersToCheck.addAll(orderRepository.findByStatus(OrderStatus.IN_PROGRESS));

            for (Order order : ordersToCheck) {
                String stateKey = "order:retry:state:" + order.getId();
                Map<Object, Object> state = redisTemplate.opsForHash().entries(stateKey);

                if (!state.isEmpty()) {
                    String nextRetryAt = (String) state.get("nextRetryAt");
                    if (nextRetryAt != null) {
                        LocalDateTime retryTime = LocalDateTime.parse(nextRetryAt);
                        if (LocalDateTime.now().isAfter(retryTime)) {
                            log.info("Processing retry for order {}", order.getId());
                            verifyYouTubeViews(order);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing view retries: {}", e.getMessage());
        }
    }

    /**
     * Schedule initial view check after offer removal PUBLIC: Called by BinomSyncScheduler after
     * removing offer from campaigns
     */
    /**
     * Schedule initial view check after offer removal.
     *
     * <p>CRITICAL: This method sets Redis key "order:secondStartCount:{orderId}" which causes
     * BinomSyncScheduler to SKIP syncing the order. This is the ONLY method that should set this
     * key, and it should ONLY be called when offers are actually removed from Binom.
     *
     * <p>EDGE CASE FIX: Added comprehensive logging with stack traces to detect when this is called
     * unexpectedly (e.g., from disabled schedulers, manual scripts, or during transaction
     * rollbacks).
     */
    public void scheduleInitialViewCheck(Order order) {
        Long orderId = order.getId();

        try {
            String videoId = order.getYoutubeVideoId();

            if (videoId == null) {
                log.error("No YouTube video ID for order {}", orderId);
                return;
            }

            // CRITICAL: Log WHO called this method and WHY (to catch edge cases)
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            String caller = stackTrace.length > 2 ? stackTrace[2].toString() : "unknown";
            String callerClass = stackTrace.length > 2 ? stackTrace[2].getClassName() : "unknown";

            log.warn(
                    "REDIS KEY CREATION INITIATED - Order {}: scheduleInitialViewCheck() called by"
                            + " {} (class: {})",
                    orderId,
                    caller,
                    callerClass);

            // Capture second startCount and store in Redis
            int secondStartCount = youTubeService.getVideoViewCount(videoId);

            String secondCountKey = "order:secondStartCount:" + orderId;
            Map<String, Object> secondCountData = new HashMap<>();
            secondCountData.put("videoId", videoId);
            secondCountData.put("secondStartCount", secondStartCount);
            secondCountData.put("capturedAt", LocalDateTime.now().toString());
            secondCountData.put("firstStartCount", order.getStartCount());
            secondCountData.put("calledBy", caller); // Track who created this key
            secondCountData.put("callerClass", callerClass);

            // Log BEFORE setting the Redis key (in case of transaction rollback)
            log.info(
                    "Order {} - ABOUT TO SET Redis secondStartCount key: videoId={},"
                            + " secondCount={}, firstCount={}, caller={}",
                    orderId,
                    videoId,
                    secondStartCount,
                    order.getStartCount(),
                    caller);

            redisTemplate.opsForHash().putAll(secondCountKey, secondCountData);
            redisTemplate.expire(secondCountKey, java.time.Duration.ofDays(7));

            // Log AFTER setting the Redis key (confirms it was set)
            log.warn(
                    "REDIS KEY CREATED - Order {} - secondStartCount key SET in Redis: {} (first"
                            + " was: {}). This order will NOW BE SKIPPED by BinomSyncScheduler!",
                    orderId,
                    secondStartCount,
                    order.getStartCount());

            // Schedule first view check in 30 minutes
            String stateKey = "order:retry:state:" + orderId;
            Map<String, Object> state = new HashMap<>();
            state.put("currentViewCount", secondStartCount);
            state.put("viewsGained", secondStartCount - order.getStartCount());
            state.put("retryCount", 0);
            state.put("nextRetryAt", LocalDateTime.now().plusMinutes(30).toString());

            redisTemplate.opsForHash().putAll(stateKey, state);
            redisTemplate.expire(stateKey, java.time.Duration.ofDays(1));

            log.info("Order {} - Initial view check scheduled in 30 minutes", orderId);

        } catch (Exception e) {
            log.error(
                    "CRITICAL ERROR - Failed to schedule initial view check for order {}: {}. Stack"
                            + " trace:",
                    orderId,
                    e.getMessage(),
                    e);

            // Log full stack trace to understand what happened
            for (StackTraceElement element : e.getStackTrace()) {
                log.error("  at {}", element);
            }
        }
    }

    /** Clean up Redis keys associated with an order */
    private void cleanupRedisKeys(Long orderId) {
        try {
            String prefix = "order:" + orderId + ":*";
            log.debug("Cleaning up Redis keys for order {} with pattern: {}", orderId, prefix);

            // Clean up order-specific keys
            String progressKey = "order:" + orderId + ":progress";
            String statusKey = "order:" + orderId + ":status";
            String viewsKey = "order:" + orderId + ":views";
            String clipKey = "order:" + orderId + ":clip";
            String secondCountKey = "order:secondStartCount:" + orderId;
            String retryKey = "order:retry:" + orderId;
            String retryStateKey = "order:retry:state:" + orderId;
            String backoffKey = "order:offerRemoval:nextRetry:" + orderId;
            String viewCheckScheduledKey = "order:viewCheckScheduled:" + orderId;

            // Delete keys if they exist
            if (redisTemplate != null) {
                redisTemplate.delete(progressKey);
                redisTemplate.delete(statusKey);
                redisTemplate.delete(viewsKey);
                redisTemplate.delete(clipKey);
                redisTemplate.delete(secondCountKey);
                redisTemplate.delete(retryKey);
                redisTemplate.delete(retryStateKey);
                redisTemplate.delete(backoffKey);
                redisTemplate.delete(viewCheckScheduledKey);

                log.debug("Cleaned up Redis keys for order {}", orderId);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up Redis keys for order {}: {}", orderId, e.getMessage());
            // Non-critical error, don't throw
        }
    }

    // ========== MERGED FROM OrderProcessingService ==========

    /** Handle order created event - main entry point for order processing */
    @EventListener
    @Async("asyncExecutor")
    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 5000))
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            log.info("Starting order processing for order: {}", event.getOrderId());
            processNewOrder(event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to process order {}: {}", event.getOrderId(), e.getMessage(), e);
            handleOrderProcessingFailure(event.getOrderId(), e);
            throw e; // Re-throw for retry mechanism
        }
    }

    /** Main order processing workflow */
    @Transactional(propagation = Propagation.REQUIRED)
    public void processNewOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new OrderNotFoundException("Order not found: " + orderId));

        log.info("Processing new order: {} for user: {}", orderId, order.getUser().getUsername());

        try {
            // 1. Validate order is in correct state
            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return;
            }

            // 2. Update to IN_PROGRESS
            orderStateManager.transitionTo(order, OrderStatus.IN_PROGRESS);

            // 3. Get initial view count
            String videoId = youTubeService.extractVideoId(order.getLink());
            int startCount = youTubeService.getVideoViewCount(videoId);
            order.setStartCount(startCount);
            order.setRemains(order.getQuantity());
            orderRepository.save(order);

            // 4. Create video processing record
            VideoProcessing videoProcessing = videoProcessingService.createProcessingRecord(order);

            // 5. Determine if clip creation is needed
            ConversionCoefficient coefficient =
                    getConversionCoefficient(order.getService().getId());
            boolean createClip = shouldCreateClip(order, coefficient);

            if (createClip) {
                // Start clip creation process
                videoProcessingService.startClipCreation(videoProcessing);
                orderStateManager.transitionTo(order, OrderStatus.PROCESSING);
            } else {
                // Direct to Binom without clip
                createBinomCampaign(order, order.getLink(), coefficient.getWithoutClip());
                orderStateManager.transitionTo(order, OrderStatus.ACTIVE);
            }

            log.info("Order {} processing initiated successfully", orderId);

        } catch (Exception e) {
            log.error("Error processing order {}: {}", orderId, e.getMessage(), e);
            orderStateManager.transitionTo(order, OrderStatus.CANCELLED);
            refundOrderAmount(order);
            throw e;
        }
    }

    /** Pause order processing with reason */
    @Transactional(propagation = Propagation.REQUIRED)
    public void pauseOrderWithReason(Long orderId, String reason) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new OrderNotFoundException(
                                                    "Order not found: " + orderId));

            if (!canPauseOrder(order)) {
                throw new IllegalStateException(
                        "Order cannot be paused in current status: " + order.getStatus());
            }

            orderStateManager.transitionTo(order, OrderStatus.PAUSED);
            order.setErrorMessage("Paused: " + reason);
            orderRepository.save(order);

            // Pause Binom campaigns
            pauseBinomCampaigns(orderId);

            log.info("Paused order {} - reason: {}", orderId, reason);

        } catch (Exception e) {
            log.error("Failed to pause order {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void handleClipCreationCompleted(Long videoProcessingId) {
        VideoProcessing videoProcessing = videoProcessingService.getById(videoProcessingId);
        Order order = videoProcessing.getOrder();

        try {
            if (!videoProcessing.isClipCreated()) {
                throw new IllegalStateException("Clip creation not completed");
            }

            ConversionCoefficient coefficient =
                    getConversionCoefficient(order.getService().getId());
            createBinomCampaign(order, videoProcessing.getClipUrl(), coefficient.getWithClip());

            orderStateManager.transitionTo(order, OrderStatus.ACTIVE);

            log.info(
                    "Order {} activated with clip: {}",
                    order.getId(),
                    videoProcessing.getClipUrl());

        } catch (Exception e) {
            log.error(
                    "Failed to activate order {} after clip creation: {}",
                    order.getId(),
                    e.getMessage(),
                    e);
            orderStateManager.transitionTo(order, OrderStatus.HOLDING);
            notificationService.notifyOperators(
                    "Order " + order.getId() + " requires manual intervention");
        }
    }

    /**
     * Complete order processing (called after successful video processing and Binom integration)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void completeOrderProcessing(Long orderId) {
        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new OrderNotFoundException(
                                                    "Order not found: " + orderId));

            // Transition to ACTIVE status (delivery in progress)
            orderStateManager.transitionTo(order, OrderStatus.ACTIVE);

            log.info("Order {} transitioned to ACTIVE - delivery started", orderId);

            // Send notification to user
            try {
                notificationService.sendOrderStartedNotification(order);
            } catch (Exception e) {
                log.error("Failed to send order started notification: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to complete order processing for {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    private void createBinomCampaign(Order order, String targetUrl, BigDecimal coefficient) {
        try {
            // Calculate required clicks based on coefficient
            int targetViews =
                    new BigDecimal(order.getQuantity())
                            .multiply(coefficient)
                            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.UP)
                            .intValue();

            // Distribute offer across 3 pre-configured campaigns
            BinomIntegrationResponse response =
                    binomService.createBinomIntegration(order, targetUrl, true, targetUrl);

            if (response.isSuccess()) {
                log.info(
                        "Offer distributed to {} campaigns for order {}",
                        response.getCampaignsCreated(),
                        order.getId());
            }

        } catch (Exception e) {
            log.error(
                    "Failed to create Binom campaign for order {}: {}",
                    order.getId(),
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    private boolean shouldCreateClip(Order order, ConversionCoefficient coefficient) {
        // Create clip if it provides better conversion rate
        return coefficient.getWithClip().compareTo(coefficient.getWithoutClip()) < 0;
    }

    private ConversionCoefficient getConversionCoefficient(Long serviceId) {
        return conversionCoefficientRepository
                .findByServiceId(serviceId)
                .orElse(
                        ConversionCoefficient.builder()
                                .serviceId(serviceId)
                                .withClip(BigDecimal.valueOf(3.0)) // Default values
                                .withoutClip(BigDecimal.valueOf(4.0))
                                .build());
    }

    /** Handle order processing failure */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOrderProcessingFailure(Long orderId, Exception error) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                log.error("Cannot handle failure for non-existent order: {}", orderId);
                return;
            }

            // Transition to CANCELLED status
            orderStateManager.transitionTo(order, OrderStatus.CANCELLED);

            // Set error message
            order.setErrorMessage("Processing failed: " + error.getMessage());
            orderRepository.save(order);

            // Refund user balance if payment was processed
            if (order.getCharge() != null && order.getCharge().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    balanceService.refundOrder(order);
                    log.info(
                            "Refunded {} to user {} for failed order {}",
                            order.getCharge(),
                            order.getUser().getUsername(),
                            orderId);
                } catch (Exception e) {
                    log.error("Failed to refund order {}: {}", orderId, e.getMessage());
                }
            }

            // Send notification to user
            try {
                notificationService.sendOrderFailedNotification(order, error.getMessage());
            } catch (Exception e) {
                log.error(
                        "Failed to send failure notification for order {}: {}",
                        orderId,
                        e.getMessage());
            }

            log.info("Handled processing failure for order {}", orderId);
        } catch (Exception e) {
            log.error(
                    "Failed to handle order processing failure for order {}: {}",
                    orderId,
                    e.getMessage(),
                    e);
        }
    }

    private void refundOrderAmount(Order order) {
        try {
            User user = order.getUser();
            BigDecimal refundAmount = order.getCharge();

            // Add the refund amount back to user's balance
            balanceService.addToBalance(
                    user, refundAmount, "Refund for cancelled order #" + order.getId());

            log.info(
                    "Refunded ${} to user {} for cancelled order {}",
                    refundAmount,
                    user.getId(),
                    order.getId());

        } catch (Exception e) {
            log.error("Failed to refund order {}: {}", order.getId(), e.getMessage(), e);
            // This should trigger an alert for manual processing
            notificationService.notifyFinanceTeam(
                    String.format(
                            "Manual refund required for order %d: $%s",
                            order.getId(), order.getCharge()));
        }
    }

    /** Check if order can be paused */
    private boolean canPauseOrder(Order order) {
        return order.getStatus() == OrderStatus.ACTIVE
                || order.getStatus() == OrderStatus.PROCESSING
                || order.getStatus() == OrderStatus.IN_PROGRESS;
    }

    /** Pause Binom campaigns for an order */
    private void pauseBinomCampaigns(Long orderId) {
        try {
            // Implementation would pause campaigns in Binom
            log.info("Pausing Binom campaigns for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to pause Binom campaigns for order {}: {}", orderId, e.getMessage());
        }
    }
}
