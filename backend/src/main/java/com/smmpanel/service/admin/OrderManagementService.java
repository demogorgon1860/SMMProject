package com.smmpanel.service.admin;

import com.smmpanel.dto.admin.DashboardStats;
import com.smmpanel.dto.admin.OrderActionRequest;
import com.smmpanel.dto.admin.OrderDto;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.exception.OrderValidationException;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.AuditService;
import com.smmpanel.service.binom.BinomCampaignService;
import com.smmpanel.service.order.state.OrderStateManager;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DECOMPOSED: Order Management Service
 *
 * <p>ARCHITECTURAL IMPROVEMENTS: 1. Single Responsibility - Only handles order management
 * operations 2. Uses OrderStateManager for safe state transitions 3. Proper integration with
 * BinomCampaignService 4. Comprehensive audit logging 5. Performance optimized queries
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderManagementService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderStateManager orderStateManager;
    private final BinomCampaignService binomCampaignService;
    private final AuditService auditService;

    /** Get dashboard statistics for admin panel */
    public DashboardStats getDashboardStats() {
        LocalDateTime last24Hours = LocalDateTime.now().minusHours(24);
        LocalDateTime last7Days = LocalDateTime.now().minusDays(7);
        LocalDateTime last30Days = LocalDateTime.now().minusDays(30);

        return DashboardStats.builder()
                .totalOrders(orderRepository.count())
                .ordersLast24h(orderRepository.countOrdersCreatedAfter(last24Hours))
                .ordersLast7Days(orderRepository.countOrdersCreatedAfter(last7Days))
                .ordersLast30Days(orderRepository.countOrdersCreatedAfter(last30Days))
                .totalRevenue(orderRepository.sumRevenueAfter(LocalDateTime.now().minusYears(1)))
                .revenueLast24h(orderRepository.sumRevenueAfter(last24Hours))
                .revenueLast7Days(orderRepository.sumRevenueAfter(last7Days))
                .revenueLast30Days(orderRepository.sumRevenueAfter(last30Days))
                .activeOrders(
                        Math.toIntExact(
                                orderRepository.countByStatusIn(
                                        Arrays.asList(
                                                OrderStatus.ACTIVE,
                                                OrderStatus.PROCESSING,
                                                OrderStatus.IN_PROGRESS))))
                .completedOrders(
                        Math.toIntExact(orderRepository.countByStatus(OrderStatus.COMPLETED)))
                .build();
    }

    /** Get orders with filtering and pagination */
    public Page<OrderDto> getOrders(
            OrderStatus status, Long userId, String search, Pageable pageable) {
        Specification<Order> spec = buildOrderSpecification(status, userId, search);
        Page<Order> orders = orderRepository.findAll(spec, pageable);
        return orders.map(this::mapToOrderDto);
    }

    /** Get order by ID with full details */
    public OrderDto getOrder(Long orderId) {
        Order order =
                orderRepository
                        .findByIdWithDetails(orderId)
                        .orElseThrow(
                                () -> new OrderValidationException("Order not found: " + orderId));

        return mapToOrderDto(order);
    }

    /** Manually complete an order */
    @Transactional
    public void completeOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new OrderValidationException("Order not found: " + orderId));

        // Use state manager for safe transition
        orderStateManager.transitionTo(order, OrderStatus.COMPLETED);

        // Stop all associated campaigns
        binomCampaignService.stopAllCampaignsForOrder(orderId);

        // Update order details
        order.setRemains(0);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        auditService.logOrderCompletion(order, "Manual completion by admin");
        log.info("Manually completed order {}", orderId);
    }

    /** Cancel an order */
    @Transactional
    public void cancelOrder(Long orderId, String reason) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new OrderValidationException("Order not found: " + orderId));

        // Use state manager for safe transition
        orderStateManager.transitionTo(order, OrderStatus.CANCELLED);

        // Stop all associated campaigns
        binomCampaignService.stopAllCampaignsForOrder(orderId);

        // Update order details
        order.setErrorMessage(reason);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        auditService.logOrderCancellation(order, reason);
        log.info("Cancelled order {} with reason: {}", orderId, reason);
    }

    /** Pause an order */
    @Transactional
    public void pauseOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new OrderValidationException("Order not found: " + orderId));

        // Use state manager for safe transition
        orderStateManager.transitionTo(order, OrderStatus.PAUSED);

        // Pause all associated campaigns
        binomCampaignService.pauseAllCampaignsForOrder(orderId);

        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        auditService.logOrderPause(order);
        log.info("Paused order {}", orderId);
    }

    /** Resume a paused order */
    @Transactional
    public void resumeOrder(Long orderId) {
        Order order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(
                                () -> new OrderValidationException("Order not found: " + orderId));

        // Use state manager for safe transition
        orderStateManager.transitionTo(order, OrderStatus.ACTIVE);

        // Resume all associated campaigns
        binomCampaignService.resumeAllCampaignsForOrder(orderId);

        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        auditService.logOrderResume(order);
        log.info("Resumed order {}", orderId);
    }

    /** Perform bulk action on multiple orders */
    @Transactional
    public void performBulkAction(List<Long> orderIds, OrderActionRequest action) {
        List<Order> orders = orderRepository.findAllById(orderIds);

        if (orders.size() != orderIds.size()) {
            throw new OrderValidationException("Some orders not found");
        }

        for (Order order : orders) {
            try {
                switch (action.getAction()) {
                    case "COMPLETE" -> completeOrder(order.getId());
                    case "CANCEL" -> cancelOrder(order.getId(), action.getReason());
                    case "PAUSE" -> pauseOrder(order.getId());
                    case "RESUME" -> resumeOrder(order.getId());
                    default ->
                            throw new OrderValidationException(
                                    "Unknown action: " + action.getAction());
                }
            } catch (Exception e) {
                log.error(
                        "Failed to perform action {} on order {}: {}",
                        action.getAction(),
                        order.getId(),
                        e.getMessage());
                // Continue with other orders
            }
        }

        auditService.logBulkOrderAction(orderIds, action.getAction());
        log.info("Performed bulk action {} on {} orders", action.getAction(), orders.size());
    }

    /** Get orders requiring attention (errors, long processing times, etc.) */
    public Page<OrderDto> getOrdersRequiringAttention(Pageable pageable) {
        LocalDateTime threshold = LocalDateTime.now().minusHours(24);

        Specification<Order> spec =
                Specification.where(
                        (root, query, cb) ->
                                cb.or(
                                        // Orders stuck in processing for too long
                                        cb.and(
                                                cb.equal(
                                                        root.get("status"), OrderStatus.PROCESSING),
                                                cb.lessThan(root.get("createdAt"), threshold)),
                                        // Orders with error messages
                                        cb.isNotNull(root.get("errorMessage")),
                                        // Orders in holding status
                                        cb.equal(root.get("status"), OrderStatus.HOLDING)));

        Page<Order> orders = orderRepository.findAll(spec, pageable);
        return orders.map(this::mapToOrderDto);
    }

    // Private helper methods

    private Specification<Order> buildOrderSpecification(
            OrderStatus status, Long userId, String search) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (userId != null) {
                predicates.add(cb.equal(root.get("user").get("id"), userId));
            }

            if (search != null && !search.trim().isEmpty()) {
                String searchPattern = "%" + search.toLowerCase() + "%";
                predicates.add(
                        cb.or(
                                cb.like(cb.lower(root.get("link")), searchPattern),
                                cb.like(cb.lower(root.get("user").get("username")), searchPattern),
                                cb.like(cb.lower(root.get("service").get("name")), searchPattern)));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private OrderDto mapToOrderDto(Order order) {
        return OrderDto.builder()
                .id(order.getId())
                .userId(order.getUser().getId())
                .username(order.getUser().getUsername())
                .serviceId(order.getService().getId())
                .serviceName(order.getService().getName())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(order.getStatus().name())
                .charge(order.getCharge())
                .processingPriority(order.getProcessingPriority())
                .errorMessage(order.getErrorMessage())
                .youtubeVideoId(order.getYoutubeVideoId())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .hasVideoProcessing(order.getVideoProcessing() != null)
                .hasBinomCampaign(
                        order.getBinomCampaigns() != null && !order.getBinomCampaigns().isEmpty())
                .build();
    }
}
