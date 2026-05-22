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
 * Service for creating and managing order refills.
 *
 * <p>Two refill modes, both gated by admin approval:
 *
 * <ul>
 *   <li>{@code PARTIAL} → top up only the under-delivered amount ({@code quantity - delivered}).
 *   <li>{@code COMPLETED} → replace the full original quantity. The panel has no background
 *       re-poll of post metrics after completion, so we can't independently detect drops; admin
 *       approval IS the drop-claim gate.
 * </ul>
 *
 * <p>{@code IN_PROGRESS} orders are not eligible — refilling while the bot is still running the
 * original would duplicate remaining work. Admin must wait for terminal status.
 *
 * <p>Guardrails: max 5 refills per order, max 1.5x of original quantity per refill, 60-second
 * idempotency window against double-click, pessimistic lock on the original order, separate
 * partial-unique index on the user-facing {@code RefillRequest} table.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefillService {

    private final OrderRepository orderRepository;
    private final OrderRefillRepository orderRefillRepository;
    private final OrderEventProducer orderEventProducer;

    private static final int MAX_REFILLS_PER_ORDER = 5;
    private static final int IDEMPOTENCY_WINDOW_SECONDS = 60;
    private static final double MAX_REFILL_QUANTITY_MULTIPLIER = 1.5;

    /** Create a refill for an order that has underdelivered Instagram actions. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RefillResponse createRefill(Long orderId) {
        log.info("[REFILL] Creating refill for order {}", orderId);

        Order originalOrder = acquireOrderLockForRefill(orderId);

        validateNoPendingRefills(orderId);
        validateNoRecentRefills(orderId);
        validateRefillLimit(orderId);
        validateOrderEligibility(originalOrder);

        // Refill quantity:
        //   - COMPLETED: replace the full original quantity. We don't have a background
        //     re-poll of post metrics after completion, so the panel can't independently
        //     "detect" drops — admin's approval IS the gate. They're trusting the customer's
        //     "I lost my followers" report and refunding the full delivery.
        //   - PARTIAL: top up only what the bot under-delivered (quantity - delivered).
        //
        // (IN_PROGRESS is rejected upstream in {@link #isEligibleForRefill} so callers
        // never reach this branch with a still-running order.)
        //
        // The {@link #MAX_REFILL_QUANTITY_MULTIPLIER} cap (1.5x of original quantity) below
        // is the hard guardrail; neither branch ever exceeds 1.0x by construction.
        Integer deliveredViews = calculateDeliveredViews(originalOrder);
        int refillQuantity =
                originalOrder.getStatus() == OrderStatus.COMPLETED
                        ? originalOrder.getQuantity()
                        : Math.max(0, originalOrder.getQuantity() - deliveredViews);

        log.info(
                "[REFILL] Order {} - Status: {}, Original: {}, Delivered: {}, RefillQty: {}",
                orderId,
                originalOrder.getStatus(),
                originalOrder.getQuantity(),
                deliveredViews,
                refillQuantity);

        if (refillQuantity <= 0) {
            throw new ApiException(
                    String.format(
                            "Order %d — nothing to refill (delivered %d/%d).",
                            orderId, deliveredViews, originalOrder.getQuantity()),
                    HttpStatus.BAD_REQUEST);
        }

        int maxAllowedRefill = (int) (originalOrder.getQuantity() * MAX_REFILL_QUANTITY_MULTIPLIER);
        if (refillQuantity > maxAllowedRefill) {
            log.error(
                    "[REFILL] SUSPICIOUS: Order {} refill quantity {} exceeds 1.5x original {}",
                    orderId,
                    refillQuantity,
                    originalOrder.getQuantity());
            throw new ApiException(
                    String.format(
                            "Refill quantity (%d) exceeds reasonable limit (max: %d).",
                            refillQuantity, maxAllowedRefill),
                    HttpStatus.BAD_REQUEST);
        }

        Integer refillNumber = getNextRefillNumber(orderId);

        log.info("[REFILL] Creating refill #{} for {} actions", refillNumber, refillQuantity);

        Order refillOrder = createRefillOrder(originalOrder, refillQuantity, refillNumber);

        OrderRefill refill =
                createRefillRecord(
                        originalOrder, refillOrder, refillNumber, deliveredViews, refillQuantity);

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

        log.info(
                "[REFILL] SUCCESS - Refill ID: {}, Refill Order ID: {}, Quantity: {}",
                refill.getId(),
                refillOrder.getId(),
                refillQuantity);

        return buildRefillResponse(refill, refillOrder);
    }

    public List<RefillResponse> getRefillHistory(Long orderId) {
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
                            return buildRefillResponse(refill, refillOrder);
                        })
                .collect(Collectors.toList());
    }

    private Order acquireOrderLockForRefill(Long orderId) {
        log.debug("[REFILL] Acquiring pessimistic lock for order {}", orderId);
        return orderRepository
                .findByIdWithLock(orderId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("Order not found with id: " + orderId));
    }

    private void validateNoPendingRefills(Long orderId) {
        long pendingRefills =
                orderRepository.countByRefillParentIdAndStatus(orderId, OrderStatus.PENDING);
        if (pendingRefills > 0) {
            throw new ApiException(
                    String.format(
                            "Order %d already has %d pending refill(s).", orderId, pendingRefills),
                    HttpStatus.CONFLICT);
        }
    }

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
                            "Refill was created %d seconds ago (refill #%d, order #%d).",
                            java.time.Duration.between(
                                            mostRecent.getCreatedAt(),
                                            java.time.LocalDateTime.now())
                                    .getSeconds(),
                            mostRecent.getRefillNumber(),
                            mostRecent.getRefillOrderId()),
                    HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private void validateRefillLimit(Long orderId) {
        long totalRefills = orderRefillRepository.countByOriginalOrderId(orderId);
        if (totalRefills >= MAX_REFILLS_PER_ORDER) {
            throw new ApiException(
                    String.format(
                            "Order %d has reached maximum refill limit (%d refills).",
                            orderId, MAX_REFILLS_PER_ORDER),
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void validateOrderEligibility(Order order) {
        if (Boolean.TRUE.equals(order.getIsRefill())) {
            throw new ApiException(
                    "Cannot refill a refill order. Please refill the original order instead.",
                    HttpStatus.BAD_REQUEST);
        }

        if (!isEligibleForRefill(order.getStatus())) {
            throw new ApiException(
                    String.format("Order status %s is not eligible for refill.", order.getStatus()),
                    HttpStatus.BAD_REQUEST);
        }
    }

    private boolean isEligibleForRefill(OrderStatus status) {
        // Only finalised states can be refilled. IN_PROGRESS is intentionally excluded —
        // approving a refill while the bot is still working on the original would
        // duplicate the remaining work (a second refill order goes out for the same target
        // URL alongside the still-running original). Admin should wait for the order to
        // settle into COMPLETED or PARTIAL before granting a refill.
        return status == OrderStatus.COMPLETED || status == OrderStatus.PARTIAL;
    }

    /**
     * Compute delivered quantity for an Instagram order. We rely on the order's tracked
     * quantity/remains progress that the bot reports via webhooks.
     */
    private Integer calculateDeliveredViews(Order order) {
        Integer remains = order.getRemains();
        Integer quantity = order.getQuantity();
        if (quantity == null) return 0;
        if (remains == null) {
            // Fall back to status: COMPLETED → fully delivered, otherwise zero.
            return order.getStatus() == OrderStatus.COMPLETED ? quantity : 0;
        }
        return Math.max(0, quantity - remains);
    }

    private Integer getNextRefillNumber(Long orderId) {
        return orderRefillRepository
                .findMaxRefillNumberByOriginalOrderId(orderId)
                .map(n -> n + 1)
                .orElse(1);
    }

    private Order createRefillOrder(Order original, Integer refillQuantity, Integer refillNumber) {
        // Refill = a new user-visible order. Without a userOrderNumber it would sort to the
        // very end of the user's /orders list (NULL → NULLS LAST under DESC) and stay invisible
        // on the first page even though the row exists. Assigning the next sequential number
        // (same pattern as OrderService.createOrder) puts the refill at the top, exactly as
        // the operator and the customer expect.
        Integer nextUserOrderNumber =
                orderRepository.findMaxUserOrderNumberByUserId(original.getUser().getId()) + 1;

        Order refillOrder =
                Order.builder()
                        .user(original.getUser())
                        .service(original.getService())
                        .link(original.getLink())
                        .quantity(refillQuantity)
                        .charge(BigDecimal.ZERO)
                        .startCount(0)
                        .remains(refillQuantity)
                        .status(OrderStatus.PENDING)
                        .userOrderNumber(nextUserOrderNumber)
                        .isRefill(true)
                        .refillParentId(original.getId())
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
            Integer refillQty) {

        OrderRefill record =
                OrderRefill.builder()
                        .originalOrderId(original.getId())
                        .refillOrderId(refillOrder.getId())
                        .refillNumber(refillNumber)
                        .originalQuantity(original.getQuantity())
                        .deliveredQuantity(delivered)
                        .refillQuantity(refillQty)
                        .startCountAtRefill(
                                original.getStartCount() != null
                                        ? Long.valueOf(original.getStartCount())
                                        : 0L)
                        .build();

        return orderRefillRepository.save(record);
    }

    private RefillResponse buildRefillResponse(OrderRefill refill, Order refillOrder) {
        return RefillResponse.builder()
                .refillId(refill.getId())
                .originalOrderId(refill.getOriginalOrderId())
                .refillOrderId(refill.getRefillOrderId())
                .refillNumber(refill.getRefillNumber())
                .originalQuantity(refill.getOriginalQuantity())
                .deliveredQuantity(refill.getDeliveredQuantity())
                .refillQuantity(refill.getRefillQuantity())
                .createdAt(refill.getCreatedAt())
                .message(
                        String.format(
                                "Refill created successfully. Order %d will deliver %d remaining"
                                        + " actions.",
                                refillOrder != null ? refillOrder.getId() : 0,
                                refill.getRefillQuantity()))
                .build();
    }

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

    private AdminRefillDto mapToAdminRefillDto(OrderRefill refill) {
        Order refillOrder =
                orderRepository
                        .findByIdWithAllDetails(refill.getRefillOrderId())
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Refill order not found: "
                                                        + refill.getRefillOrderId()));

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
                .build();
    }
}
