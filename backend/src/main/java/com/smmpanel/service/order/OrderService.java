package com.smmpanel.service.order;

import com.smmpanel.config.OrderQuotaCheckProperties;
import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.*;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.OrderNotFoundException;
import com.smmpanel.exception.OrderQuotaExceededException;
import com.smmpanel.exception.OrderValidationException;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.auth.ApiKeyService;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.CqrsReadModelService;
import com.smmpanel.service.core.EventSourcingService;
import com.smmpanel.service.fraud.FraudDetectionService;
import com.smmpanel.service.integration.InstagramService;
import com.smmpanel.service.notification.NotificationService;
import com.smmpanel.service.notification.TelegramNotificationService;
import com.smmpanel.service.order.state.OrderStateManager;
import com.smmpanel.service.settings.AppSettingsService;
import com.smmpanel.service.validation.OrderValidationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
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
    private final OrderStateManagementService orderStateManagementService;
    private final EventSourcingService eventSourcingService;
    private final CqrsReadModelService cqrsReadModelService;
    private final ApiKeyService apiKeyService;
    private final InstagramService instagramService;
    private final com.smmpanel.client.InstagramBotClient instagramBotClient;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final OrderStateManager orderStateManager;
    private final NotificationService notificationService;
    private final TelegramNotificationService telegramNotificationService;
    private final com.smmpanel.service.core.ServiceService serviceService;
    private final OrderValidationService orderValidationService;
    private final FraudDetectionService fraudDetectionService;
    private final OrderEventProducer orderEventProducer;
    private final OrderQuotaCheckProperties quotaCheckProperties;
    private final AppSettingsService appSettingsService;

    /**
     * Statuses that occupy a slot on a URL — either reserved (pending/active work) or already
     * delivered (completed/partial). Terminal statuses (CANCELLED/FAILED/ERROR/REFILL/SUSPENDED)
     * release their slot and must NOT be counted. Must stay aligned with the WHERE clause of the
     * partial index {@code idx_orders_quota_check}.
     */
    private static final List<OrderStatus> QUOTA_COUNTING_STATUSES =
            List.of(
                    OrderStatus.PENDING,
                    OrderStatus.IN_PROGRESS,
                    OrderStatus.PROCESSING,
                    OrderStatus.ACTIVE,
                    OrderStatus.PARTIAL,
                    OrderStatus.COMPLETED,
                    OrderStatus.PAUSED,
                    OrderStatus.HOLDING);

    /**
     * Strictly *in-flight* statuses for the per-user concurrent-orders cap. Excludes terminal/
     * completed states (COMPLETED, PARTIAL) — those don't occupy a worker slot anymore — but keeps
     * everything that's still consuming resources or could resume work.
     */
    private static final List<OrderStatus> QUOTA_COUNTING_INFLIGHT_STATUSES =
            List.of(
                    OrderStatus.PENDING,
                    OrderStatus.IN_PROGRESS,
                    OrderStatus.PROCESSING,
                    OrderStatus.ACTIVE,
                    OrderStatus.PAUSED,
                    OrderStatus.HOLDING);

    // Redis namespace constants
    private static final String REDIS_ORDER_PROGRESS = "order:progress:";
    private static final int REDIS_PROGRESS_TTL_HOURS = 24;

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

            // 3. Handle custom comments services - auto-calculate quantity from comments
            int effectiveQuantity = request.getQuantity();

            if (isCustomCommentsService(service)) {
                // For custom comments services, quantity = number of comment lines
                if (request.getCustomComments() == null
                        || request.getCustomComments().trim().isEmpty()) {
                    throw new OrderValidationException(
                            "Custom comments are required for this service. "
                                    + "Please provide comments (one per line).");
                }

                // Parse and validate comments (also checks 2200 char limit per comment)
                int commentCount = parseAndValidateCustomComments(request.getCustomComments());

                if (commentCount == 0) {
                    throw new OrderValidationException(
                            "No valid comments found. Please provide at least one comment.");
                }

                // Auto-set quantity from comment count (ignore user-provided quantity)
                effectiveQuantity = commentCount;
                log.info(
                        "Custom comments service [{}]: auto-calculated quantity={} from {} comment"
                                + " lines",
                        service.getName(),
                        effectiveQuantity,
                        commentCount);
            }

            // 4. Validate effective quantity against service limits
            if (effectiveQuantity < service.getMinOrder()
                    || effectiveQuantity > service.getMaxOrder()) {
                throw new OrderValidationException(
                        String.format(
                                "Quantity must be between %d and %d. Got: %d%s",
                                service.getMinOrder(),
                                service.getMaxOrder(),
                                effectiveQuantity,
                                isCustomCommentsService(service)
                                        ? " (based on number of comments)"
                                        : ""));
            }

            // 5. Calculate charge based on effective quantity (markup applied if configured)
            BigDecimal charge = calculateCharge(service, effectiveQuantity);

            // 5a. Reject orders below the configured platform-wide minimum charge.
            // This is a money-floor — guarantees the panel doesn't process sub-cent orders that
            // cost more in payment processing than they earn.
            BigDecimal minCharge =
                    appSettingsService.getDecimal(
                            AppSettingsService.KEY_MIN_ORDER_CHARGE, BigDecimal.ZERO);
            if (minCharge.signum() > 0 && charge.compareTo(minCharge) < 0) {
                throw new OrderValidationException(
                        String.format(
                                "Order amount $%s is below the minimum charge of $%s",
                                charge.toPlainString(), minCharge.toPlainString()));
            }

            // 5b. Concurrent-orders quota — count this user's in-flight orders and reject if at
            // or above the configured cap. 0 means "unlimited" (skip the check entirely).
            int maxConcurrent =
                    appSettingsService.getInt(AppSettingsService.KEY_MAX_CONCURRENT_ORDERS, 0);
            if (maxConcurrent > 0) {
                long inFlight =
                        orderRepository.countByUserIdAndStatusIn(
                                user.getId(), QUOTA_COUNTING_INFLIGHT_STATUSES);
                if (inFlight >= maxConcurrent) {
                    throw new OrderQuotaExceededException(
                            String.format(
                                    "Concurrent orders limit reached: %d in-flight, max %d",
                                    inFlight, maxConcurrent));
                }
            }

            // 6. Create order first (optimistic approach)
            Order order = new Order();
            order.setUser(user);
            order.setService(service);
            order.setLink(
                    normalizeInstagramUrl(request.getLink())); // Normalize /reel/, /reels/ → /p/
            order.setQuantity(effectiveQuantity); // Use calculated quantity
            order.setCharge(charge);
            order.setStartCount(0);
            order.setRemains(effectiveQuantity); // Use calculated quantity
            order.setStatus(OrderStatus.PENDING);
            order.setProcessingPriority(0);
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());
            order.setCustomComments(request.getCustomComments());

            // Set user-specific order number (1, 2, 3... per user)
            Integer maxUserOrderNumber =
                    orderRepository.findMaxUserOrderNumberByUserId(user.getId());
            order.setUserOrderNumber(maxUserOrderNumber + 1);

            // Per-URL quota check — prevents bypass via multiple orders on the same link
            enforceUrlQuota(service, order.getLink(), effectiveQuantity);

            order = orderRepository.save(order);

            // 7. Save order event for event sourcing
            eventSourcingService.saveOrderEvent(
                    order,
                    "ORDER_CREATED",
                    Map.of("service", service.getName(), "quantity", effectiveQuantity));

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

            // 9. Dispatch Instagram order for async processing
            if (isInstagramOrder(order)) {
                log.info(
                        "Order {} - Instagram order detected, publishing OrderCreatedEvent",
                        order.getId());

                OrderCreatedEvent instagramOrderEvent = new OrderCreatedEvent();
                instagramOrderEvent.setOrderId(order.getId());
                instagramOrderEvent.setUserId(user.getId());
                instagramOrderEvent.setServiceId(service.getId());
                instagramOrderEvent.setQuantity(order.getQuantity());
                instagramOrderEvent.setTimestamp(LocalDateTime.now());

                orderEventProducer.publishOrderCreatedEvent(instagramOrderEvent);
                log.info("Published Instagram OrderCreatedEvent for order {}", order.getId());
            } else {
                log.info(
                        "Order {} - Not an Instagram order, skipping special processing",
                        order.getId());
            }

            log.info(
                    "Order {} created successfully for user {}", order.getId(), user.getUsername());

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

        // Change status to REFILL — Instagram refill flow handled by InstagramService
        order.setStatus(OrderStatus.REFILL);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

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
                || order.getStatus().equals(OrderStatus.CANCELLED)
                || order.getStatus().equals(OrderStatus.PARTIAL)) {
            throw new OrderValidationException(
                    "Cannot cancel order in " + order.getStatus() + " status");
        }

        // CRITICAL: Set remains to full quantity ONLY if not already partially delivered
        // This ensures correct refund calculation
        if (order.getRemains() == null || order.getRemains().equals(order.getQuantity())) {
            // Nothing delivered yet - set remains to full quantity for full refund
            order.setRemains(order.getQuantity());
        }
        // If remains < quantity, keep current remains for partial refund

        // Cancel order and refund balance
        BigDecimal refundAmount = calculateRefund(order);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(
                    user, refundAmount, order, "Refund for cancelled order " + orderId);
        }

        // CRITICAL: Set charge to 0 after full refund (cancelled = nothing paid)
        order.setCharge(BigDecimal.ZERO);

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.info(
                "Order {} cancelled by user {}, refund: {}, remains set to {}",
                orderId,
                user.getUsername(),
                refundAmount,
                order.getQuantity());
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
        // CRITICAL: Set remains to full quantity (payment failed = nothing delivered)
        order.setRemains(order.getQuantity());
        // CRITICAL: Set charge to 0 (payment failed = nothing was paid)
        order.setCharge(BigDecimal.ZERO);
        order.setStatus(OrderStatus.CANCELLED);
        order.setErrorMessage("Payment failed");
        orderRepository.save(order);
    }

    @Transactional
    public void startProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PROCESSING);
        orderRepository.save(order);
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
        if (order.getStatus() != OrderStatus.ACTIVE
                && order.getStatus() != OrderStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Can only pause ACTIVE or PROCESSING orders, current status: "
                            + order.getStatus());
        }
        order.setStatus(OrderStatus.PAUSED);
        orderRepository.save(order);
    }

    @Transactional
    public void resumeOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PAUSED) {
            throw new IllegalStateException(
                    "Can only resume PAUSED orders, current status: " + order.getStatus());
        }
        order.setStatus(OrderStatus.ACTIVE);
        orderRepository.save(order);
    }

    @Transactional
    public void markPartialCompletion(Long orderId, Integer completedQuantity) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PARTIAL);
        int remains = order.getQuantity() - completedQuantity;
        order.setRemains(remains);

        // CRITICAL: Update charge to reflect only delivered portion
        // charge = originalCharge * (completed / quantity)
        if (order.getQuantity() > 0 && completedQuantity >= 0) {
            BigDecimal deliveredRatio =
                    BigDecimal.valueOf(completedQuantity)
                            .divide(
                                    BigDecimal.valueOf(order.getQuantity()),
                                    4,
                                    java.math.RoundingMode.HALF_UP);
            order.setCharge(order.getCharge().multiply(deliveredRatio));
        }

        orderRepository.save(order);
    }

    @Transactional
    public void markOrderComplete(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.COMPLETED);
        order.setRemains(0);
        orderRepository.save(order);
    }

    /**
     * User-initiated cancel of one of their own orders. Three things have to happen, in order:
     *
     * <ol>
     *   <li><b>Ownership + status guard</b> — own orders only, and only while still cancellable
     *       (PENDING / ACTIVE / IN_PROGRESS / PROCESSING). The frontend Cancel button is shown
     *       under exactly the same set, so the backend rejection is unreachable in normal UX —
     *       the check stays as a defense-in-depth boundary.
     *   <li><b>Tell the bot to stop</b> — best-effort {@code cancelOrderFast}. Without this, a
     *       user could click Cancel on an ACTIVE/PROCESSING order, get a refund, and let the
     *       bot finish delivery for free. Failure here is logged and swallowed: the panel is
     *       the source of truth, and the webhook handlers (InstagramService /
     *       InstagramResultConsumer) skip late-arriving callbacks once the order is already
     *       in a terminal state.
     *   <li><b>Refund</b> — full when nothing was delivered (status → CANCELLED), pro-rata
     *       partial when {@code views_delivered > 0} (status → PARTIAL). The math lives in
     *       {@link InstagramService#processPartialRefund} / {@link
     *       InstagramService#processFullRefund} which already handle the BigDecimal rounding,
     *       balance update, and charge mutation atomically with this transaction.
     * </ol>
     *
     * @param orderId the order ID
     * @param username the username of the requesting user (for ownership check)
     */
    @Transactional
    public void cancelOrder(Long orderId, String username) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () ->
                                        new OrderValidationException(
                                                "Order not found: " + orderId));

        if (!order.getUser().getUsername().equals(username)) {
            throw new OrderValidationException("You can only cancel your own orders");
        }

        OrderStatus status = order.getStatus();
        if (status != OrderStatus.PENDING
                && status != OrderStatus.ACTIVE
                && status != OrderStatus.IN_PROGRESS
                && status != OrderStatus.PROCESSING) {
            throw new OrderValidationException(
                    "Order cannot be cancelled in current status: " + status);
        }

        // (2) Best-effort: tell the bot fleet to stop dispatching. Deferred until AFTER our
        // refund + status-change has committed — same reason as AdminService.stopBotForOrder
        // (see commit e421c64c). Running the bot signal inline would let the bot's response
        // webhook race the panel-side save: bot replies "cancelled, completed=N",
        // InstagramResultConsumer commits its own update, our @Version then mismatches →
        // StaleStateException → rollback → user sees "An unexpected error", refund silently
        // reverts, but the bot has already forgotten the order ⇒ zombie. Deferring guarantees
        // the webhook arrives after we've committed CANCELLED/PARTIAL, and the consumer's
        // terminal-state guard skips it.
        final String botOrderId = order.getInstagramBotOrderId();
        if (botOrderId != null && !botOrderId.isBlank()) {
            com.smmpanel.util.AfterCommitRunner.runAfterCommit(
                    () -> {
                        try {
                            instagramBotClient.cancelOrderFast(botOrderId);
                        } catch (Exception e) {
                            log.warn(
                                    "User-cancel: bot cancel for order {} failed (refund"
                                            + " already issued, panel state already committed):"
                                            + " {}",
                                    orderId,
                                    e.getMessage());
                        }
                    });
        }

        int delivered = order.getViewsDelivered() != null ? order.getViewsDelivered() : 0;
        int quantity = order.getQuantity() != null ? order.getQuantity() : 0;

        // Race guard: bot may have already finished delivery but the webhook hasn't reached
        // us yet (status still IN_PROGRESS in our DB). A full refund here would be free
        // service — the user got everything and gets every cent back. Refuse and let the
        // webhook drive the natural COMPLETED transition.
        if (quantity > 0 && delivered >= quantity) {
            throw new OrderValidationException(
                    "Order has already been fully delivered — wait for the system to mark"
                            + " it completed");
        }

        if (delivered > 0) {
            // (3a) Partial — keep the delivered fraction, refund the rest.
            instagramService.processPartialRefund(
                    order, delivered, "Order cancelled by user (partial)");
            order.setRemains(quantity - delivered);
            order.setStatus(OrderStatus.PARTIAL);
            order.setTrafficStatus("PARTIAL_BY_USER");
        } else {
            // (3b) Full — nothing delivered yet.
            instagramService.processFullRefund(order, "Order cancelled by user");
            order.setRemains(quantity);
            order.setStatus(OrderStatus.CANCELLED);
            order.setTrafficStatus("CANCELLED_BY_USER");
        }

        order.setOperatorNotes("user-cancel:" + username);
        orderRepository.save(order);
    }

    /** Internal cancel without ownership check. Used by state machine and system processes. */
    @Transactional
    public void cancelOrderInternal(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setRemains(order.getQuantity());
        order.setCharge(BigDecimal.ZERO);
        order.setStatus(OrderStatus.CANCELLED);
        order.setOperatorNotes(reason);
        orderRepository.save(order);
    }

    @Transactional
    public void cancelOrderWithRefund(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        // CRITICAL: Set remains to full quantity BEFORE calculating refund
        order.setRemains(order.getQuantity());
        order.setStatus(OrderStatus.CANCELLED);
        order.setOperatorNotes(reason);

        // Process refund
        BigDecimal refundAmount = calculateRefund(order);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(order.getUser(), refundAmount, order, "Refund: " + reason);
        }

        // CRITICAL: Set charge to 0 after full refund (cancelled = nothing paid)
        order.setCharge(BigDecimal.ZERO);
        orderRepository.save(order);
    }

    @Transactional
    public void retryProcessing(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.PROCESSING);
        order.setLastRetryAt(LocalDateTime.now());
        orderRepository.save(order);
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
        // Instagram orders are dispatched via OrderEventProducer at creation time;
        // this method exists for state-machine compatibility but is now a no-op.
        log.debug("triggerOrderProcessing called for order {} (no-op for Instagram flow)", orderId);
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
        BigDecimal effectiveRate = applyMarkup(service.getPricePer1000());
        return effectiveRate
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Apply the configured platform markup percentage to a base rate. A markup of 15% multiplies by
     * 1.15. Markups are non-negative; a 0% setting returns the base rate unchanged. The same
     * function is used for charge calculation AND for the rate shown in {@code /v1/services} so
     * customers always see exactly what they will pay.
     */
    private BigDecimal applyMarkup(BigDecimal baseRate) {
        if (baseRate == null) return BigDecimal.ZERO;
        BigDecimal markupPct =
                appSettingsService.getDecimal(
                        AppSettingsService.KEY_MARKUP_PERCENT, BigDecimal.ZERO);
        if (markupPct.signum() <= 0) return baseRate;
        BigDecimal multiplier =
                BigDecimal.ONE.add(
                        markupPct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        return baseRate.multiply(multiplier);
    }

    /**
     * Reject the order if the cumulative consumed quantity for this service's <em>quota group</em>
     * on {@code normalizedLink} within the configured window, plus the new {@code quantity}, would
     * exceed the service's {@code maxOrder}.
     *
     * <p>The quota group is "action+gender" — the service name with its trailing {@code [geo]}
     * bracket stripped (see {@link #quotaGroupKey}). All geo variants and both copies in the
     * duplicated id-space (1-25 / 26-50) share the bot's account pool for that action+gender, so
     * they are summed together; counting a single service id let resellers pile repeat orders onto
     * one link through sibling services until the bot ran out of fresh accounts and returned
     * PARTIAL. Genders stay in separate groups: their account pools are disjoint and their caps
     * differ (male 140 / female 300 / mix 500), and the cap is uniform within a group so {@code
     * service.getMaxOrder()} is well defined.
     *
     * <p>Acquires a transaction-scoped advisory lock on (group, link) first to serialize concurrent
     * createOrder calls on the same link within the group, preventing read-skew between two
     * parallel requests. Throws before {@code orderRepository.save(...)} so a rejected order is
     * never persisted and no balance is debited.
     */
    private void enforceUrlQuota(
            com.smmpanel.entity.Service service, String normalizedLink, int quantity) {
        if (!quotaCheckProperties.isEnabled()) {
            return;
        }

        String groupKey = quotaGroupKey(service.getName());
        List<Long> groupServiceIds = serviceRepository.findActiveQuotaGroupServiceIds(groupKey);
        if (groupServiceIds.isEmpty()) {
            // Defensive: the ordered service is active, so the resolver should always return at
            // least its id. If a name shape we didn't anticipate yields no match, fall back to
            // this service alone so the cap is still enforced rather than silently skipped.
            groupServiceIds = List.of(service.getId());
        }

        orderRepository.acquireQuotaLock(groupKey, normalizedLink);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(quotaCheckProperties.getWindowDays());
        long consumed =
                orderRepository.sumConsumedQuantityByServiceIdsAndLink(
                        groupServiceIds, normalizedLink, QUOTA_COUNTING_STATUSES, cutoff);

        if (consumed + (long) quantity > service.getMaxOrder()) {
            throw new OrderQuotaExceededException(
                    String.format(
                            "URL quota exceeded for \"%s\" on %s: %d already consumed in last %d"
                                    + " days, requested %d, group max %d",
                            groupKey,
                            normalizedLink,
                            consumed,
                            quotaCheckProperties.getWindowDays(),
                            quantity,
                            service.getMaxOrder()));
        }
    }

    /**
     * Quota group key for a service name: the name with a trailing {@code [geo]} bracket removed,
     * e.g. {@code "Instagram Followers [Mix Gender] [USA/Europe]"} → {@code "Instagram Followers
     * [Mix Gender]"}. Collapses geo variants and the duplicated id-space into one action+gender
     * group. Names without a trailing bracket (e.g. {@code "Instagram MIX GEO Followers"}) are
     * returned unchanged and form their own group. Must stay in sync with the LIKE pattern in
     * {@link com.smmpanel.repository.jpa.ServiceRepository#findActiveQuotaGroupServiceIds}.
     */
    static String quotaGroupKey(String serviceName) {
        if (serviceName == null) {
            return "";
        }
        return serviceName.replaceFirst("\\s*\\[[^\\]]*\\]\\s*$", "").trim();
    }

    private BigDecimal calculateRefund(Order order) {
        return calculateRefund(order.getCharge(), order.getQuantity(), order.getRemains());
    }

    /**
     * Pure-function refund formula. Extracted from {@link #calculateRefund(Order)} so the math is
     * unit-testable in isolation and the edge cases (zero quantity, overdelivery, BigDecimal
     * precision) can't silently regress.
     *
     * <p>Formula: {@code refund = charge * remains / quantity}, equivalent to {@code charge * (1 -
     * completed / quantity)} where {@code completed = quantity - remains}.
     *
     * <p>Edge cases handled defensively:
     *
     * <ul>
     *   <li>{@code charge == null} or {@code charge <= 0} → ZERO (nothing was charged → nothing to
     *       refund).
     *   <li>{@code quantity <= 0} → ZERO. The field is {@code @Min(1)} on the validator so this
     *       shouldn't happen in production, but guarding defends the wallet against a manual SQL
     *       write or a corrupt import.
     *   <li>{@code remains <= 0} → ZERO (fully delivered, no refund).
     *   <li>{@code remains >= quantity} → full charge (overdelivery / nothing-delivered both end up
     *       here; we never refund more than was charged).
     * </ul>
     *
     * <p>Result is rounded to two decimal places (cents) using HALF_UP — the same rounding mode the
     * wallet uses, so the credit row matches the expected amount to the cent.
     */
    static BigDecimal calculateRefund(BigDecimal charge, int quantity, Integer remains) {
        if (charge == null || charge.signum() <= 0) return BigDecimal.ZERO;
        if (quantity <= 0) return BigDecimal.ZERO;
        if (remains == null || remains <= 0) return BigDecimal.ZERO;
        if (remains >= quantity) return charge.setScale(2, java.math.RoundingMode.HALF_UP);

        BigDecimal ratio =
                BigDecimal.valueOf(remains)
                        .divide(BigDecimal.valueOf(quantity), 6, java.math.RoundingMode.HALF_UP);
        return charge.multiply(ratio).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private OrderResponse mapToOrderResponse(Order order) {
        return OrderResponse.builder()
                // ALWAYS the DB id — the field is the canonical identifier the API contract
                // is built around (action=status, action=statuses, action=refill all look up
                // by it). A previous version of this mapper aliased userOrderNumber here as
                // a "nicer" display number; that was latent for years (the column was almost
                // always NULL) until a backfill populated it for every row, at which point
                // resellers started getting back small numbers like "1" from action=add and
                // then querying action=status&order=1 — which silently resolved to whoever's
                // DB id 1 was, returning a totally different order's data. Never alias the id.
                .id(order.getId())
                .service(order.getService().getId().intValue())
                .serviceName(order.getService().getName())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(mapToPerfectPanelStatus(order.getStatus()))
                .charge(calculateEffectiveCharge(order).toString())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .isRefill(Boolean.TRUE.equals(order.getIsRefill()))
                .build();
    }

    private BigDecimal calculateEffectiveCharge(Order order) {
        if (order.getCharge() == null) {
            return BigDecimal.ZERO;
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return BigDecimal.ZERO;
        }
        // For PARTIAL orders, charge is already proportional (set by markPartialCompletion)
        return order.getCharge();
    }

    private Map<String, Object> mapToServiceMap(com.smmpanel.entity.Service service) {
        Map<String, Object> serviceMap = new HashMap<>();
        serviceMap.put("service", service.getId());
        serviceMap.put("name", service.getName());
        serviceMap.put("category", service.getCategory());
        // Show the marked-up rate to clients — what they see here matches what
        // calculateCharge will bill them.
        serviceMap.put("rate", applyMarkup(service.getPricePer1000()).toString());
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

    /** Check if order is for Instagram service */
    private boolean isInstagramOrder(Order order) {
        if (order.getService() == null) return false;
        String category = order.getService().getCategory();
        String serviceName = order.getService().getName();
        return (category != null && category.toLowerCase().contains("instagram"))
                || (serviceName != null && serviceName.toLowerCase().contains("instagram"));
    }

    /**
     * Check if service is a custom comments service (requires user-provided comments). Custom
     * comment services have "custom" AND "comment" in their name.
     */
    private boolean isCustomCommentsService(com.smmpanel.entity.Service service) {
        if (service == null || service.getName() == null) return false;
        String name = service.getName().toLowerCase();
        return name.contains("custom") && name.contains("comment");
    }

    /** Instagram comment character limit */
    private static final int MAX_COMMENT_LENGTH = 2200;

    /**
     * Parse and validate custom comments. Returns the count of valid non-empty comment lines.
     * Throws OrderValidationException if any comment exceeds MAX_COMMENT_LENGTH.
     */
    private int parseAndValidateCustomComments(String customComments) {
        if (customComments == null || customComments.trim().isEmpty()) {
            return 0;
        }

        String[] lines = customComments.split("\n");
        int validCommentCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("GENDER:")) {
                if (trimmed.length() > MAX_COMMENT_LENGTH) {
                    throw new OrderValidationException(
                            String.format(
                                    "Comment on line %d is too long (%d characters). Maximum"
                                            + " allowed: %d characters.",
                                    i + 1, trimmed.length(), MAX_COMMENT_LENGTH));
                }
                validCommentCount++;
            }
        }

        return validCommentCount;
    }

    /**
     * Normalize Instagram URL:
     *
     * <ul>
     *   <li>Convert /reel/ and /reels/ to /p/ (bot expects /p/ format)
     *   <li>Strip query parameters (?igsh=, ?utm_source=, etc.)
     *   <li>Strip mobile sharing tokens appended to post shortcodes
     * </ul>
     *
     * <p>Examples:
     * <li>https://www.instagram.com/reels/ABC123/ → https://www.instagram.com/p/ABC123/
     * <li>https://www.instagram.com/p/ABC123/?igsh=... → https://www.instagram.com/p/ABC123/
     * <li>https://www.instagram.com/p/ABC123xyzSHARINGTOKEN → https://www.instagram.com/p/ABC123/
     * <li>https://www.instagram.com/username/?igsh=... → https://www.instagram.com/username/
     */
    private static final java.util.regex.Pattern INSTAGRAM_HOST_PATTERN =
            java.util.regex.Pattern.compile("(?i)(https?://)?([a-z0-9.]*instagram\\.com)");

    private static final java.util.regex.Pattern SHORTCODE_TOKEN_PATTERN =
            java.util.regex.Pattern.compile("(?i)(/p/)([A-Za-z0-9_-]{11})[A-Za-z0-9_-]+/?$");

    /**
     * First-path-segment keywords that are NOT usernames. When the first segment after the host is
     * one of these, the URL points at content (a post, reel, tv, story, …) whose shortcode is
     * case-SENSITIVE base64 and must be left untouched. Any other first segment is a profile
     * handle, which is case-INSENSITIVE on Instagram and gets lowercased in {@link
     * #normalizeInstagramUrl} so the per-URL quota treats Chrimbu and chrimbu as one target.
     */
    private static final java.util.Set<String> RESERVED_PATH_SEGMENTS =
            java.util.Set.of("p", "reel", "reels", "tv", "stories", "explore", "s", "tagged");

    /**
     * Normalize Instagram URL. Non-Instagram URLs are returned unchanged.
     *
     * <ul>
     *   <li>Normalize protocol and domain: Www.INstagram.com → https://www.instagram.com
     *   <li>Convert /reel/ and /reels/ to /p/ (case-insensitive)
     *   <li>Strip query parameters (?igsh=, ?utm_source=, etc.)
     *   <li>Strip mobile sharing tokens appended to 11-char shortcodes
     *   <li>Ensure trailing slash
     * </ul>
     */
    // Package-private static (no instance state — only static patterns and the static logger) so
    // the canonicalization that the per-URL quota and the v2026.06 link backfill both depend on can
    // be unit-tested directly. The SQL backfill must stay in sync with this method.
    static String normalizeInstagramUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        url = url.trim();
        String original = url;

        // Bare username / @username → wrap into full profile URL.
        // Matches only when the input has no slashes, no whitespace, and no instagram.com.
        if (!url.toLowerCase().contains("instagram.com")
                && !url.contains("/")
                && !url.contains(" ")) {
            String handle = url.startsWith("@") ? url.substring(1) : url;
            if (handle.matches("^[A-Za-z0-9._]{1,30}$")) {
                url = "https://www.instagram.com/" + handle.toLowerCase() + "/";
                log.debug("Normalized Instagram handle: {} -> {}", original, url);
                return url;
            }
        }

        // Only normalize Instagram URLs — leave YouTube etc. untouched
        if (!url.toLowerCase().contains("instagram.com")) {
            return url;
        }

        // Ensure https:// protocol
        if (!url.toLowerCase().startsWith("http://") && !url.toLowerCase().startsWith("https://")) {
            url = "https://" + url;
        }
        if (url.toLowerCase().startsWith("http://")) {
            url = "https://" + url.substring(7);
        }

        // Lowercase the domain portion only (preserve shortcode case)
        java.util.regex.Matcher domainMatcher =
                java.util.regex.Pattern.compile("^(https://[^/]+)(/.*)$").matcher(url);
        if (domainMatcher.matches()) {
            url = domainMatcher.group(1).toLowerCase() + domainMatcher.group(2);
        }

        // Canonicalize the Instagram host to www.instagram.com. The bare-handle branch above
        // already emits www.instagram.com, but full URLs entered as instagram.com/... or
        // m.instagram.com/... otherwise stayed on a different host — storing the same profile
        // under two hosts, which split the per-URL quota into separate buckets. Case-insensitive
        // (?i) so a pathless mixed-case host like https://M.instagram.com is also collapsed,
        // matching the SQL backfill's case-insensitive host rewrite.
        url = url.replaceFirst("(?i)^https://(www\\.|m\\.)?instagram\\.com", "https://www.instagram.com");

        // Strip query parameters — Instagram never needs them for content access
        int queryIndex = url.indexOf('?');
        if (queryIndex != -1) {
            url = url.substring(0, queryIndex);
        }

        // Strip fragment
        int fragmentIndex = url.indexOf('#');
        if (fragmentIndex != -1) {
            url = url.substring(0, fragmentIndex);
        }

        // Convert /reels/ and /reel/ to /p/ (case-insensitive)
        url = url.replaceAll("(?i)/reels/", "/p/");
        url = url.replaceAll("(?i)/reel/", "/p/");

        // Strip leading '@' from profile path: instagram.com/@username/ → instagram.com/username/
        url = url.replaceAll("(?i)(instagram\\.com)/@([A-Za-z0-9._]+)", "$1/$2");

        // Strip mobile sharing tokens from post shortcodes.
        // Instagram shortcodes are exactly 11 characters. Mobile share links append
        // a sharing token directly to the path without separator:
        //   /p/DW0O1I1jbSJONCifAAQ8kaVxavBX--zQUgtyoc0 → /p/DW0O1I1jbSJ/
        java.util.regex.Matcher postMatcher = SHORTCODE_TOKEN_PATTERN.matcher(url);
        if (postMatcher.find()) {
            url = url.substring(0, postMatcher.start()) + "/p/" + postMatcher.group(2) + "/";
        }

        // Lowercase the profile handle. Instagram usernames are case-insensitive, so
        // instagram.com/Chrimbu and instagram.com/chrimbu are the same profile and must collapse
        // to one quota key. Only fires when the first path segment is a username — content URLs
        // (/p/, /tv/, …) carry a case-SENSITIVE shortcode and are left untouched. /reel(s)/ were
        // already rewritten to /p/ above.
        java.util.regex.Matcher handleMatcher =
                java.util.regex.Pattern.compile("^(https://www\\.instagram\\.com)/([^/]+)(/.*)?$")
                        .matcher(url);
        if (handleMatcher.matches()
                && !RESERVED_PATH_SEGMENTS.contains(handleMatcher.group(2).toLowerCase())) {
            url =
                    handleMatcher.group(1)
                            + "/"
                            + handleMatcher.group(2).toLowerCase()
                            + (handleMatcher.group(3) == null ? "" : handleMatcher.group(3));
        }

        // Ensure trailing slash
        if (!url.endsWith("/")) {
            url = url + "/";
        }

        if (!original.equals(url)) {
            log.debug("Normalized Instagram URL: {} -> {}", original, url);
        }

        return url;
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
                        .customComments(request.getCustomComments())
                        .build();

        return createOrder(orderCreateRequest, username);
    }

    @Transactional
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

        // Validate user has access to this service
        serviceService.validateUserAccessToService(user, service.getId());

        // Handle custom comments services - auto-calculate quantity from comments
        int effectiveQuantity = request.getQuantity();

        if (isCustomCommentsService(service)) {
            // For custom comments services, quantity = number of comment lines
            if (request.getCustomComments() == null
                    || request.getCustomComments().trim().isEmpty()) {
                throw new OrderValidationException(
                        "Custom comments are required for this service. "
                                + "Please provide comments (one per line).");
            }

            // Parse and validate comments (also checks 2200 char limit per comment)
            int commentCount = parseAndValidateCustomComments(request.getCustomComments());

            if (commentCount == 0) {
                throw new OrderValidationException(
                        "No valid comments found. Please provide at least one comment.");
            }

            // Auto-set quantity from comment count
            effectiveQuantity = commentCount;
            log.info(
                    "Custom comments service [{}]: quantity auto-set to {} based on comment count",
                    service.getName(),
                    effectiveQuantity);
        }

        // Validate effective quantity
        if (effectiveQuantity < service.getMinOrder()
                || effectiveQuantity > service.getMaxOrder()) {
            throw new OrderValidationException(
                    String.format(
                            "Quantity must be between %d and %d. Got: %d%s",
                            service.getMinOrder(),
                            service.getMaxOrder(),
                            effectiveQuantity,
                            isCustomCommentsService(service)
                                    ? " (based on number of comments)"
                                    : ""));
        }

        // Calculate charge based on effective quantity
        BigDecimal charge = calculateCharge(service, effectiveQuantity);

        // Check balance
        if (user.getBalance().compareTo(charge) < 0) {
            throw new OrderValidationException("Insufficient balance");
        }

        // Create order
        Order order = new Order();
        order.setUser(user);
        order.setService(service);
        order.setLink(normalizeInstagramUrl(request.getLink())); // Normalize /reel/, /reels/ → /p/
        order.setQuantity(effectiveQuantity);
        order.setCharge(charge);
        order.setRemains(effectiveQuantity);
        order.setStartCount(0);
        order.setStatus(OrderStatus.PENDING);
        order.setProcessingPriority(0);
        order.setCustomComments(request.getCustomComments());

        // Per-user sequential number. The other createOrder overload sets this (line ~245)
        // but only inside the dead {@code createOrderWithApiKey} method — every real entry
        // path (panel UI POST /v1/orders and Perfect Panel /api/v2?action=add) lands here,
        // so without this assignment {@code user_order_number} stays NULL and the order
        // sorts to the bottom of the user's /orders list (NULLS LAST under DESC). That's
        // why customer rows were "invisible" on page 1.
        Integer maxUserOrderNumber = orderRepository.findMaxUserOrderNumberByUserId(user.getId());
        order.setUserOrderNumber(maxUserOrderNumber + 1);

        // Auto-set customComments for emoji comment services (Perfect Panel API compatibility)
        Long serviceId = service.getId();
        if (serviceId == 11L
                && (request.getCustomComments() == null || request.getCustomComments().isEmpty())) {
            order.setCustomComments("EMOJI:POSITIVE");
        } else if (serviceId == 12L
                && (request.getCustomComments() == null || request.getCustomComments().isEmpty())) {
            order.setCustomComments("EMOJI:NEGATIVE");
        }

        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());

        // Per-URL quota check — prevents bypass via multiple orders on the same link
        enforceUrlQuota(service, order.getLink(), effectiveQuantity);

        order = orderRepository.save(order);

        // Send Telegram notification for new order
        telegramNotificationService.notifyNewOrder(order);

        // Deduct balance
        balanceService.deductBalance(user, charge, order, "Order #" + order.getId());

        // Dispatch Instagram order for async processing
        if (isInstagramOrder(order)) {
            log.info(
                    "Order {} - Instagram order detected, publishing OrderCreatedEvent",
                    order.getId());

            OrderCreatedEvent instagramOrderEvent = new OrderCreatedEvent();
            instagramOrderEvent.setOrderId(order.getId());
            instagramOrderEvent.setUserId(user.getId());
            instagramOrderEvent.setServiceId(service.getId());
            instagramOrderEvent.setQuantity(order.getQuantity());
            instagramOrderEvent.setTimestamp(LocalDateTime.now());

            orderEventProducer.publishOrderCreatedEvent(instagramOrderEvent);
            log.info("Published Instagram OrderCreatedEvent for order {}", order.getId());
        } else {
            log.info(
                    "Order {} - Not an Instagram order, skipping special processing",
                    order.getId());
        }

        log.info("Order {} created successfully for user {}", order.getId(), username);

        return mapToOrderResponse(order);
    }

    public OrderResponse getOrder(Long orderId, String username) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));
        // Get order and verify ownership
        // Uses findByIdWithDetails to eagerly load Service (prevents LazyInitializationException)
        Order order =
                orderRepository
                        .findByIdWithDetails(orderId)
                        .orElseThrow(() -> new OrderValidationException("Order not found"));

        if (!order.getUser().getId().equals(user.getId())) {
            throw new OrderValidationException("Order not found");
        }

        return mapToOrderResponse(order);
    }

    public Page<OrderResponse> getUserOrders(
            String username, String status, String search, Pageable pageable) {
        return getUserOrders(username, status, search, false, pageable);
    }

    public Page<OrderResponse> getUserOrders(
            String username, String status, String search, boolean refillOnly, Pageable pageable) {
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));

        // Default sort by userOrderNumber DESC if no sort specified
        if (pageable.getSort().isUnsorted()) {
            pageable =
                    org.springframework.data.domain.PageRequest.of(
                            pageable.getPageNumber(),
                            pageable.getPageSize(),
                            org.springframework.data.domain.Sort.by(
                                    org.springframework.data.domain.Sort.Direction.DESC,
                                    "userOrderNumber"));
        }

        // "Refill" tab on /orders — filter by is_refill = true. Status is intentionally
        // ignored (refill rows traverse PENDING → IN_PROGRESS → COMPLETED/PARTIAL over their
        // lifetime; the bucket is "all my refills") but search must still apply — without it
        // the operator types a non-matching id/link and the page silently shows every refill
        // instead of the "No orders match" empty state. Parses the search term the same way
        // the regular branch below does (numeric → exact id, otherwise → link substring).
        if (refillOnly) {
            Long refillSearchId = null;
            String refillSearchLink = null;
            if (search != null && !search.isEmpty()) {
                String term = search.trim().toLowerCase();
                try {
                    refillSearchId = Long.parseLong(term);
                } catch (NumberFormatException ignored) {
                    refillSearchLink = "%" + term + "%";
                }
            }
            return orderRepository
                    .searchUserAndIsRefillTrue(user, refillSearchId, refillSearchLink, pageable)
                    .map(this::mapToOrderResponse);
        }

        // Resolve the requested status once. mapFromPerfectPanelStatus returns null for blank /
        // unknown input — when null we treat it the same as "no filter" rather than silently
        // narrowing to PENDING (which is what the old default did and is why "In progress" /
        // "Cancelled" / "Processing" tabs returned no orders on prod).
        OrderStatus orderStatus =
                (status == null || status.isEmpty()) ? null : mapFromPerfectPanelStatus(status);

        // When no search term, use original optimized repository methods. We coalesce
        // IN_PROGRESS / PROCESSING into a single bucket on the way to the DB — the panel UI
        // hides PROCESSING entirely and renders both as "In progress", so a click on the
        // "In progress" tab must surface orders in either real status (otherwise PROCESSING
        // orders silently vanish from the user's list).
        if (search == null || search.isEmpty()) {
            Page<Order> orders;
            if (orderStatus == null) {
                orders = orderRepository.findByUser(user, pageable);
            } else if (orderStatus == OrderStatus.IN_PROGRESS
                    || orderStatus == OrderStatus.PROCESSING) {
                orders =
                        orderRepository.findByUserAndStatusIn(
                                user,
                                java.util.List.of(OrderStatus.IN_PROGRESS, OrderStatus.PROCESSING),
                                pageable);
            } else {
                orders = orderRepository.findByUserAndStatus(user, orderStatus, pageable);
            }
            return orders.map(this::mapToOrderResponse);
        }

        // With search: use JPQL-based repository query (avoids Specification issues
        // with partitioned tables)
        String term = search.trim().toLowerCase();
        try {
            Long orderId = Long.parseLong(term);
            // Search by exact order ID
            Page<Order> orders =
                    orderStatus != null
                            ? orderRepository.searchByUserAndIdAndStatus(
                                    user, orderId, orderStatus, pageable)
                            : orderRepository.searchByUserAndId(user, orderId, pageable);
            return orders.map(this::mapToOrderResponse);
        } catch (NumberFormatException e) {
            // Search by link
            Page<Order> orders =
                    orderStatus != null
                            ? orderRepository.searchByUserAndLinkAndStatus(
                                    user, "%" + term + "%", orderStatus, pageable)
                            : orderRepository.searchByUserAndLink(user, "%" + term + "%", pageable);
            return orders.map(this::mapToOrderResponse);
        }
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

    /**
     * Resolve a status string into the matching {@link OrderStatus}. Accepts:
     *
     * <ul>
     *   <li>Native enum names (case-insensitive): {@code IN_PROGRESS}, {@code CANCELLED}, etc. This
     *       is what the SMMWorld frontend sends (tab.toUpperCase()).
     *   <li>PerfectPanel-style human strings: {@code "in progress"}, {@code "canceled"} (US
     *       spelling), {@code "partial"}, etc. Reseller integrations send these via /api/v2.
     * </ul>
     *
     * Returns {@code null} when the input doesn't map to any known status — the caller treats a
     * {@code null} filter the same as "any". Returning {@code PENDING} as a default (the old
     * behavior) silently changed the filter the user picked, so picking "In progress" actually
     * showed pending orders, which is the bug this method had on prod.
     */
    private OrderStatus mapFromPerfectPanelStatus(String status) {
        if (status == null || status.isBlank()) return null;
        // Native enum match first (FRONTEND CASE): IN_PROGRESS, CANCELLED, COMPLETED, ...
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException ignored) {
            // Fall through to legacy PerfectPanel aliases below.
        }
        // PerfectPanel-style aliases for backwards compatibility.
        return switch (status.trim().toLowerCase()) {
            case "pending" -> OrderStatus.PENDING;
            case "in progress", "in_progress", "inprogress" -> OrderStatus.IN_PROGRESS;
            case "processing" -> OrderStatus.PROCESSING;
            case "active" -> OrderStatus.ACTIVE;
            case "partial" -> OrderStatus.PARTIAL;
            case "completed" -> OrderStatus.COMPLETED;
            case "canceled", "cancelled" -> OrderStatus.CANCELLED;
            case "paused" -> OrderStatus.PAUSED;
            case "holding" -> OrderStatus.HOLDING;
            case "refill" -> OrderStatus.REFILL;
            case "error" -> OrderStatus.ERROR;
            case "suspended" -> OrderStatus.SUSPENDED;
            default -> null;
        };
    }

    // Optimized delegate methods
    public Page<OrderResponse> getUserOrdersOptimized(
            String username, String status, String search, Pageable pageable) {
        return getUserOrders(username, status, search, pageable);
    }

    public OrderResponse getOrderOptimized(Long orderId, String username) {
        return getOrder(orderId, username);
    }

    public Map<String, Object> getOrdersBatchOptimized(String apiKey, String[] orderIds) {
        return getMultipleOrderStatus(apiKey, orderIds);
    }

    public Map<Long, OrderResponse> getOrdersBatchOptimized(List<Long> orderIds, String username) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }
        User user =
                userRepository
                        .findByUsername(username)
                        .orElseThrow(() -> new OrderValidationException("User not found"));

        // SQL-side filter on (id IN :ids AND user_id = :userId) with JOIN FETCH for user and
        // service. The previous implementation pulled every order this user had ever placed —
        // thousands of rows for active resellers — and filtered the candidate IDs in memory.
        // That dominated the response time of the Perfect Panel statuses endpoint, which
        // resellers poll every few seconds with batches of 25–50 IDs. The new query reads
        // exactly the rows the caller asked for; authorization stays inside the WHERE clause
        // so a malicious caller can't probe other users' order IDs.
        List<Order> orders = orderRepository.findByIdInAndUserId(orderIds, user.getId());
        Map<Long, OrderResponse> result = new HashMap<>(orders.size());
        for (Order order : orders) {
            result.put(order.getId(), mapToOrderResponse(order));
        }
        return result;
    }

    /** Lightweight progress update — Instagram orders update remains via webhook/RabbitMQ. */
    public void updateOrderProgress(Long orderId, Integer clicksDelivered, Integer totalClicks) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
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

    /** Main order processing workflow — Instagram orders are dispatched via OrderEventProducer. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void processNewOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new OrderNotFoundException("Order not found: " + orderId));

        log.info("Processing new order: {} for user: {}", orderId, order.getUser().getUsername());

        try {
            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return;
            }

            // Transition to IN_PROGRESS — Instagram delivery is owned by InstagramService.
            orderStateManager.transitionTo(order, OrderStatus.IN_PROGRESS);
            order.setRemains(order.getQuantity());
            orderRepository.save(order);

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

            log.info("Paused order {} - reason: {}", orderId, reason);

        } catch (Exception e) {
            log.error("Failed to pause order {}: {}", orderId, e.getMessage());
            throw e;
        }
    }

    /** Complete order processing (transition to ACTIVE — delivery in progress) */
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
}
