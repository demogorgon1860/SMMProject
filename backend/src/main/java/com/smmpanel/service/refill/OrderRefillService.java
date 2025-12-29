package com.smmpanel.service.refill;

import com.smmpanel.dto.admin.AdminRefillDto;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderRefill;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.producer.OrderEventProducer;
import com.smmpanel.repository.jpa.OrderRefillRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.integration.YouTubeService;
import com.smmpanel.service.video.YouTubeProcessingHelper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for creating and managing order refills. Handles underdelivered orders by creating new
 * orders for the remaining quantity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefillService {

    private final OrderRepository orderRepository;
    private final OrderRefillRepository orderRefillRepository;
    private final YouTubeService youtubeService;
    private final YouTubeProcessingHelper youtubeProcessingHelper;
    private final OrderEventProducer orderEventProducer;

    private static final int MAX_REFILLS_PER_ORDER = 5;
    private static final int IDEMPOTENCY_WINDOW_SECONDS = 60;
    private static final double MAX_REFILL_QUANTITY_MULTIPLIER = 1.5;

    /**
     * Create a refill for an order that has underdelivered views
     *
     * <p>THREAD-SAFE: Uses pessimistic locking to prevent concurrent refills IDEMPOTENT: Prevents
     * duplicate refills within 60-second window ATOMIC: Uses database-level refill number
     * generation
     *
     * @param orderId The original order ID to refill
     * @return RefillResponse with details of the created refill
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RefillResponse createRefill(Long orderId) {
        log.info("[REFILL] Creating refill for order {}", orderId);

        // Step 1: PESSIMISTIC LOCK - Acquire exclusive lock on order row
        // Prevents concurrent refills for same order
        Order originalOrder = acquireOrderLockForRefill(orderId);

        // Step 2: DUPLICATE PREVENTION - Check for pending refills
        validateNoPendingRefills(orderId);

        // Step 3: IDEMPOTENCY - Check for recent refills (within 60 seconds)
        validateNoRecentRefills(orderId);

        // Step 4: MAX REFILLS - Enforce limit (5 refills max)
        validateRefillLimit(orderId);

        // Step 5: Validate order is eligible for refill
        validateOrderEligibility(originalOrder);

        // Step 6: Get current YouTube view count
        Long currentViewCount = fetchCurrentViewCount(originalOrder);

        // Step 7: Calculate delivered quantity
        Integer deliveredViews =
                calculateDeliveredViews(originalOrder.getStartCount(), currentViewCount);

        log.info(
                "[REFILL] Order {} - Original: {}, Start Count: {}, Current: {}, Delivered: {}",
                orderId,
                originalOrder.getQuantity(),
                originalOrder.getStartCount(),
                currentViewCount,
                deliveredViews);

        // Step 8: Calculate refill quantity
        Integer refillQuantity = originalOrder.getQuantity() - deliveredViews;

        // VALIDATION: Check if refill is needed
        if (refillQuantity <= 0) {
            throw new ApiException(
                    String.format(
                            "Order %d has been fully delivered. Delivered: %d, Ordered: %d",
                            orderId, deliveredViews, originalOrder.getQuantity()),
                    HttpStatus.BAD_REQUEST);
        }

        // SANITY CHECK: Prevent absurdly large refills (e.g., view count bug)
        // Refill should never exceed 1.5x original quantity
        int maxAllowedRefill = (int) (originalOrder.getQuantity() * MAX_REFILL_QUANTITY_MULTIPLIER);
        if (refillQuantity > maxAllowedRefill) {
            log.error(
                    "[REFILL] SUSPICIOUS: Order {} refill quantity {} exceeds 1.5x original {}",
                    orderId,
                    refillQuantity,
                    originalOrder.getQuantity());
            throw new ApiException(
                    String.format(
                            "Refill quantity (%d) exceeds reasonable limit (max: %d). "
                                    + "This may indicate a view count error. Please check YouTube"
                                    + " views manually.",
                            refillQuantity, maxAllowedRefill),
                    HttpStatus.BAD_REQUEST);
        }

        // Step 9: ATOMIC - Get refill number
        // DB unique constraint ensures no duplicates even under concurrent load
        Integer refillNumber = getNextRefillNumber(orderId);

        log.info("[REFILL] Creating refill #{} for {} views", refillNumber, refillQuantity);

        // Step 10: Create refill order (FREE - user already paid)
        Order refillOrder = createRefillOrder(originalOrder, refillQuantity, refillNumber);

        // Step 11: Create refill tracking record
        OrderRefill refill =
                createRefillRecord(
                        originalOrder,
                        refillOrder,
                        refillNumber,
                        deliveredViews,
                        refillQuantity,
                        currentViewCount);

        // Step 12: Publish order created event to Kafka for processing
        // CRITICAL: Use originalOrder for user/service IDs (has eagerly-loaded relationships)
        // refillOrder has lazy-loaded proxies after save()
        OrderCreatedEvent event =
                new OrderCreatedEvent(this, refillOrder.getId(), originalOrder.getUser().getId());
        event.setServiceId(originalOrder.getService().getId());
        event.setQuantity(refillOrder.getQuantity());
        event.setTimestamp(LocalDateTime.now());
        event.setCreatedAt(refillOrder.getCreatedAt());

        log.info(
                "[REFILL] Publishing order created event for refill order: orderId={}, userId={}",
                refillOrder.getId(),
                originalOrder.getUser().getId());

        orderEventProducer.publishOrderCreatedEvent(event);

        // Step 13: Refill order will be picked up by Kafka consumer
        // The order has status=PENDING and will be processed like a regular order
        // BinomService will detect isRefill=true and route to campaign 5
        log.info(
                "[REFILL] ✅ SUCCESS - Refill ID: {}, Refill Order ID: {}, Quantity: {}. "
                        + "Event published to Kafka. Order will be processed by normal pipeline"
                        + " and routed to campaign 5.",
                refill.getId(),
                refillOrder.getId(),
                refillQuantity);

        return buildRefillResponse(refill, refillOrder, currentViewCount);
    }

    /**
     * Get refill history for an order
     *
     * @param orderId The original order ID
     * @return List of refills for the order
     */
    public List<RefillResponse> getRefillHistory(Long orderId) {
        // Validate order exists
        if (!orderRepository.existsById(orderId)) {
            throw new ResourceNotFoundException("Order not found with id: " + orderId);
        }

        List<OrderRefill> refills =
                orderRefillRepository.findByOriginalOrderIdOrderByRefillNumberAsc(orderId);

        return refills.stream()
                .map(
                        refill -> {
                            Order refillOrder =
                                    orderRepository
                                            .findById(refill.getRefillOrderId())
                                            .orElse(null);
                            return buildRefillResponse(
                                    refill, refillOrder, refill.getStartCountAtRefill());
                        })
                .collect(Collectors.toList());
    }

    // ========== CONCURRENCY CONTROL & VALIDATION ==========

    /**
     * Acquire pessimistic write lock on order PREVENTS: Concurrent refill creation for same order
     */
    private Order acquireOrderLockForRefill(Long orderId) {
        log.debug("[REFILL] Acquiring pessimistic lock for order {}", orderId);
        return orderRepository
                .findByIdWithLock(orderId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Order not found with id: " + orderId));
    }

    /**
     * Check for pending refills PREVENTS: Creating refill while another is pending prevents double
     * refills
     */
    private void validateNoPendingRefills(Long orderId) {
        long pendingRefills =
                orderRepository.countByRefillParentIdAndStatus(orderId, OrderStatus.PENDING);
        if (pendingRefills > 0) {
            throw new ApiException(
                    String.format(
                            "Order %d already has %d pending refill(s). Wait for current refill to"
                                    + " complete before creating another.",
                            orderId, pendingRefills),
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Check for recent refills (idempotency) PREVENTS: Duplicate refills from retry/double-click
     */
    private void validateNoRecentRefills(Long orderId) {
        java.time.LocalDateTime cutoff =
                java.time.LocalDateTime.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);

        List<OrderRefill> recentRefills =
                orderRefillRepository.findByOriginalOrderIdOrderByRefillNumberAsc(orderId).stream()
                        .filter(r -> r.getCreatedAt().isAfter(cutoff))
                        .collect(java.util.stream.Collectors.toList());

        if (!recentRefills.isEmpty()) {
            OrderRefill mostRecent = recentRefills.get(recentRefills.size() - 1);
            throw new ApiException(
                    String.format(
                            "Refill was created %d seconds ago (refill #%d, order #%d). "
                                    + "Please wait %d seconds before creating another refill.",
                            java.time.Duration.between(
                                            mostRecent.getCreatedAt(),
                                            java.time.LocalDateTime.now())
                                    .getSeconds(),
                            mostRecent.getRefillNumber(),
                            mostRecent.getRefillOrderId(),
                            IDEMPOTENCY_WINDOW_SECONDS),
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    /** Enforce maximum refills limit PREVENTS: Infinite refill loops / abuse */
    private void validateRefillLimit(Long orderId) {
        long totalRefills = orderRefillRepository.countByOriginalOrderId(orderId);
        if (totalRefills >= MAX_REFILLS_PER_ORDER) {
            throw new ApiException(
                    String.format(
                            "Order %d has reached maximum refill limit (%d refills). "
                                    + "No further refills allowed.",
                            orderId, MAX_REFILLS_PER_ORDER),
                    HttpStatus.BAD_REQUEST);
        }
    }

    /** Validate order is eligible for refill */
    private void validateOrderEligibility(Order order) {
        // Cannot refill a refill order directly
        if (order.getIsRefill()) {
            throw new ApiException(
                    "Cannot refill a refill order. Please refill the original order instead.",
                    HttpStatus.BAD_REQUEST);
        }

        // Check order status eligibility
        if (!isEligibleForRefill(order.getStatus())) {
            throw new ApiException(
                    String.format(
                            "Order status %s is not eligible for refill. Only COMPLETED,"
                                    + " IN_PROGRESS, or PARTIAL orders can be refilled.",
                            order.getStatus()),
                    HttpStatus.BAD_REQUEST);
        }

        // Must have start count to calculate delivered views
        if (order.getStartCount() == null || order.getStartCount() == 0) {
            throw new ApiException(
                    "Order does not have a valid start count. Cannot calculate delivered views.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isEligibleForRefill(OrderStatus status) {
        return status == OrderStatus.COMPLETED
                || status == OrderStatus.IN_PROGRESS
                || status == OrderStatus.PARTIAL;
    }

    private Long fetchCurrentViewCount(Order order) {
        try {
            String videoId = youtubeProcessingHelper.extractVideoId(order.getLink());
            Long viewCount = youtubeService.getViewCount(videoId);

            if (viewCount == null || viewCount == 0) {
                log.warn(
                        "YouTube API returned null or zero view count for order {}, video: {}",
                        order.getId(),
                        videoId);
                throw new ApiException(
                        "Unable to fetch current view count from YouTube. Please try again later.",
                        HttpStatus.SERVICE_UNAVAILABLE);
            }

            return viewCount;

        } catch (Exception e) {
            log.error("Failed to fetch current view count for order {}", order.getId(), e);
            throw new ApiException(
                    "Failed to fetch current view count: " + e.getMessage(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private Integer calculateDeliveredViews(Integer startCount, Long currentCount) {
        if (startCount == null) startCount = 0;
        return Math.max(0, (int) (currentCount - startCount));
    }

    private Integer getNextRefillNumber(Long orderId) {
        return orderRefillRepository
                .findMaxRefillNumberByOriginalOrderId(orderId)
                .map(n -> n + 1)
                .orElse(1);
    }

    private Order createRefillOrder(Order original, Integer refillQuantity, Integer refillNumber) {
        // Create new order for refill (FREE - charge = 0)
        Order refillOrder =
                Order.builder()
                        .user(original.getUser())
                        .service(original.getService())
                        .link(original.getLink()) // SAME VIDEO
                        .quantity(refillQuantity) // REMAINING QUANTITY
                        .charge(BigDecimal.ZERO) // FREE REFILL
                        .startCount(0) // Will be updated when processing starts
                        .remains(refillQuantity)
                        .status(OrderStatus.PENDING)
                        .isRefill(true)
                        .refillParentId(original.getId())
                        .coefficient(original.getCoefficient()) // Use same coefficient
                        .targetCountry(original.getTargetCountry())
                        .targetViews(refillQuantity)
                        .build();

        refillOrder = orderRepository.save(refillOrder);

        log.info(
                "Created refill order {} for original order {} (refill #{})",
                refillOrder.getId(),
                original.getId(),
                refillNumber);

        return refillOrder;
    }

    private OrderRefill createRefillRecord(
            Order original,
            Order refillOrder,
            Integer refillNumber,
            Integer delivered,
            Integer refillQty,
            Long currentCount) {

        OrderRefill record =
                OrderRefill.builder()
                        .originalOrderId(original.getId())
                        .refillOrderId(refillOrder.getId())
                        .refillNumber(refillNumber)
                        .originalQuantity(original.getQuantity())
                        .deliveredQuantity(delivered)
                        .refillQuantity(refillQty)
                        .startCountAtRefill(currentCount)
                        .build();

        return orderRefillRepository.save(record);
    }

    private RefillResponse buildRefillResponse(
            OrderRefill refill, Order refillOrder, Long currentViewCount) {
        return RefillResponse.builder()
                .refillId(refill.getId())
                .originalOrderId(refill.getOriginalOrderId())
                .refillOrderId(refill.getRefillOrderId())
                .refillNumber(refill.getRefillNumber())
                .originalQuantity(refill.getOriginalQuantity())
                .deliveredQuantity(refill.getDeliveredQuantity())
                .refillQuantity(refill.getRefillQuantity())
                .currentViewCount(currentViewCount)
                .createdAt(refill.getCreatedAt())
                .message(
                        String.format(
                                "Refill created successfully. Order %d will deliver %d remaining"
                                        + " views.",
                                refillOrder != null ? refillOrder.getId() : 0,
                                refill.getRefillQuantity()))
                .build();
    }

    /**
     * Get all refills across all orders for admin view. Returns paginated list of refills with
     * order details.
     *
     * @param pageable Pagination parameters
     * @return Map containing refills list and pagination metadata
     */
    public Map<String, Object> getAllRefills(Pageable pageable) {
        log.debug("[REFILL] Fetching all refills with pagination: {}", pageable);

        Page<OrderRefill> refillsPage = orderRefillRepository.findAll(pageable);

        List<AdminRefillDto> refillDtos =
                refillsPage.getContent().stream()
                        .map(this::mapToAdminRefillDto)
                        .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("refills", refillDtos);
        response.put("totalPages", refillsPage.getTotalPages());
        response.put("totalElements", refillsPage.getTotalElements());
        response.put("currentPage", refillsPage.getNumber());
        response.put("pageSize", refillsPage.getSize());

        return response;
    }

    /** Map OrderRefill entity to AdminRefillDto with order and user details */
    private AdminRefillDto mapToAdminRefillDto(OrderRefill refill) {
        // Fetch the refill order with user eagerly loaded to prevent LazyInitializationException
        Order refillOrder =
                orderRepository
                        .findByIdWithAllDetails(refill.getRefillOrderId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Refill order not found: "
                                                        + refill.getRefillOrderId()));

        // Extract video ID from link
        String videoId = null;
        try {
            videoId = youtubeProcessingHelper.extractVideoId(refillOrder.getLink());
        } catch (Exception e) {
            log.warn("Failed to extract video ID from link: {}", refillOrder.getLink());
        }

        // Build order name: "Order #{originalOrderId} - Refill #{refillNumber}"
        String orderName =
                String.format(
                        "Order #%d - Refill #%d",
                        refill.getOriginalOrderId(), refill.getRefillNumber());

        return AdminRefillDto.builder()
                .refillId(refill.getId())
                .originalOrderId(refill.getOriginalOrderId())
                .refillOrderId(refill.getRefillOrderId())
                .refillNumber(refill.getRefillNumber())
                .originalQuantity(refill.getOriginalQuantity())
                .deliveredQuantity(refill.getDeliveredQuantity())
                .refillQuantity(refill.getRefillQuantity())
                .startCountAtRefill(refill.getStartCountAtRefill())
                .username(refillOrder.getUser().getUsername())
                .link(refillOrder.getLink())
                .status(refillOrder.getStatus().toString())
                .startCount(refillOrder.getStartCount())
                .remains(refillOrder.getRemains())
                .refillCreatedAt(refill.getCreatedAt())
                .orderName(orderName)
                .binomOfferId(refillOrder.getBinomOfferId())
                .youtubeVideoId(videoId)
                .build();
    }
}
