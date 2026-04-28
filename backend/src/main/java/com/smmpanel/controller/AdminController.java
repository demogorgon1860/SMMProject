package com.smmpanel.controller;

import com.smmpanel.dto.admin.*;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.admin.AdminService;
import com.smmpanel.service.admin.SystemHealthService;
import com.smmpanel.service.refill.OrderRefillService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminController {

    private final AdminService adminService;
    private final SystemHealthService systemHealthService;
    private final OrderRefillService orderRefillService;
    private final com.smmpanel.producer.OrderEventProducer orderEventProducer;
    private final com.smmpanel.repository.jpa.OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final com.smmpanel.service.admin.AdminAuditService adminAuditService;
    private final com.smmpanel.repository.jpa.AdminAuditLogRepository adminAuditLogRepository;

    /** Resolve the operator behind the current request, or {@code null} if anonymous. */
    private User getCurrentOperatorOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) return null;
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardStats> getDashboardStats() {
        DashboardStats stats = adminService.getDashboardStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * Daily breakdown for the last N days (default 30, capped at 90). Powers the Profit + Orders
     * charts on the admin dashboard. Returns one entry per day, including zero-volume days, so the
     * client can render a contiguous N-day series without rebucketing.
     */
    @GetMapping("/stats/daily")
    public ResponseEntity<List<DailyStatPoint>> getDailyStats(
            @RequestParam(defaultValue = "30") int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        return ResponseEntity.ok(adminService.getDailyStats(safeDays));
    }

    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            // Explicit URL substring search. The shared `search` param tries to disambiguate
            // (id vs. username vs. URL based on the contents); for short fragments that don't
            // contain '/' or 'instagram' that heuristic falsely treats them as username. The
            // dedicated param removes the ambiguity and lets the server-side index match
            // across ALL pages instead of the broken client-side per-page filter.
            @RequestParam(required = false) String urlSearch,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Pageable pageable) {

        Map<String, Object> orders =
                adminService.getAllOrders(status, search, urlSearch, dateFrom, dateTo, pageable);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/deposits")
    public ResponseEntity<Map<String, Object>> getAllDeposits(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Pageable pageable) {

        Map<String, Object> deposits =
                adminService.getAllDeposits(status, username, dateFrom, dateTo, pageable);
        return ResponseEntity.ok(deposits);
    }

    @PostMapping("/orders/{orderId}/actions")
    public ResponseEntity<PerfectPanelResponse> performOrderAction(
            @PathVariable Long orderId, @Valid @RequestBody OrderActionRequest request) {

        String act = request.getAction().toLowerCase();
        String reason = request.getReason();
        switch (act) {
            case "stop":
                adminService.pauseOrder(orderId, reason);
                adminAuditService.record(
                        "order.pause",
                        "ORDER",
                        orderId,
                        "Order #" + orderId,
                        reason == null || reason.isBlank() ? "Paused order" : "Paused: " + reason);
                break;
            case "resume":
            case "start":
                adminService.resumeOrder(orderId);
                adminAuditService.record(
                        "order.resume", "ORDER", orderId, "Order #" + orderId, "Resumed order");
                break;
            case "refill":
                adminService.refillOrder(orderId, request.getNewQuantity());
                adminAuditService.record(
                        "order.refill",
                        "ORDER",
                        orderId,
                        "Order #" + orderId,
                        "Refilled with new quantity " + request.getNewQuantity());
                break;
            case "cancel":
                adminService.cancelOrder(orderId, reason);
                adminAuditService.record(
                        "order.cancel",
                        "ORDER",
                        orderId,
                        "Order #" + orderId,
                        reason == null || reason.isBlank()
                                ? "Cancelled order"
                                : "Cancelled: " + reason);
                break;
            case "delete":
                adminService.deleteOrder(orderId);
                adminAuditService.record(
                        "order.delete", "ORDER", orderId, "Order #" + orderId, "Deleted order");
                break;
            case "update_start_count":
                adminService.updateStartCount(orderId, request.getNewStartCount());
                adminAuditService.record(
                        "order.update_start_count",
                        "ORDER",
                        orderId,
                        "Order #" + orderId,
                        "Updated start count to " + request.getNewStartCount());
                break;
            case "complete":
                adminService.completeOrder(orderId);
                adminAuditService.record(
                        "order.complete", "ORDER", orderId, "Order #" + orderId, "Completed order");
                break;
            case "force_complete":
                adminService.forceCompleteOrder(orderId, reason, getCurrentOperatorOrNull());
                adminAuditService.record(
                        "order.force_complete",
                        "ORDER",
                        orderId,
                        "Order #" + orderId,
                        reason == null || reason.isBlank()
                                ? "Force-completed order"
                                : "Force-completed: " + reason);
                break;
            case "partial":
                if (request.getRemains() != null) {
                    adminService.markOrderAsPartialWithRemains(
                            orderId, reason, request.getRemains());
                } else {
                    adminService.markOrderAsPartial(orderId, reason);
                }
                adminAuditService.record(
                        "order.mark_partial",
                        "ORDER",
                        orderId,
                        "Order #" + orderId,
                        "Marked partial"
                                + (request.getRemains() != null
                                        ? " with remains=" + request.getRemains()
                                        : "")
                                + (reason == null || reason.isBlank() ? "" : " · " + reason));
                break;
            default:
                return ResponseEntity.badRequest()
                        .body(
                                PerfectPanelResponse.error(
                                        "Unknown action: " + request.getAction(), 400));
        }

        return ResponseEntity.ok(
                PerfectPanelResponse.builder()
                        .data(Map.of("message", "Action completed successfully"))
                        .success(true)
                        .build());
    }

    @PostMapping("/orders/bulk-actions")
    public ResponseEntity<PerfectPanelResponse> performBulkAction(
            @Valid @RequestBody BulkActionRequest request) {

        int processed = adminService.performBulkAction(request);

        return ResponseEntity.ok(
                PerfectPanelResponse.builder()
                        .data(Map.of("message", "Bulk action completed", "processed", processed))
                        .success(true)
                        .build());
    }

    /**
     * Create a refill for an order that has underdelivered views. Creates a new order for the
     * remaining quantity.
     */
    @PostMapping("/orders/{orderId}/refill")
    public ResponseEntity<RefillResponse> createRefill(@PathVariable Long orderId) {
        RefillResponse response = orderRefillService.createRefill(orderId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get refill history for an order. Shows all refills that have been created for the original
     * order.
     */
    @GetMapping("/orders/{orderId}/refills")
    public ResponseEntity<List<RefillResponse>> getRefillHistory(@PathVariable Long orderId) {
        List<RefillResponse> refills = orderRefillService.getRefillHistory(orderId);
        return ResponseEntity.ok(refills);
    }

    /**
     * Get all refills across all orders for admin view. Returns paginated list with order details.
     */
    @GetMapping("/refills")
    public ResponseEntity<Map<String, Object>> getAllRefills(Pageable pageable) {
        Map<String, Object> refills = orderRefillService.getAllRefills(pageable);
        return ResponseEntity.ok(refills);
    }

    @GetMapping("/conversion-coefficients")
    public ResponseEntity<List<CoefficientDto>> getConversionCoefficients() {
        List<CoefficientDto> coefficients = adminService.getConversionCoefficients();
        return ResponseEntity.ok(coefficients);
    }

    @PutMapping("/conversion-coefficients/{serviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CoefficientDto> updateConversionCoefficient(
            @PathVariable Long serviceId, @Valid @RequestBody CoefficientUpdateRequest request) {
        CoefficientDto updated = adminService.updateConversionCoefficient(serviceId, request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Live infrastructure status strip on the admin dashboard. Probes Spring Boot, Postgres, Redis,
     * RabbitMQ, the Instagram bot, and Cryptomus in parallel under a hard 2s per-check budget. The
     * endpoint always returns within ~2.5s — a stuck dependency surfaces as a single {@code "down"}
     * tile, never as a Cloudflare 504 like the previous implementation.
     */
    @GetMapping("/system/health")
    public ResponseEntity<List<SystemHealthComponent>> getSystemHealth() {
        return ResponseEntity.ok(systemHealthService.probe());
    }

    @GetMapping("/logs/operator")
    public ResponseEntity<Map<String, Object>> getOperatorLogs(
            @RequestParam(required = false) String operatorUsername,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Pageable pageable) {

        Map<String, Object> logs =
                adminService.getOperatorLogs(operatorUsername, action, dateFrom, dateTo, pageable);
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/stats/revenue")
    public ResponseEntity<Map<String, Object>> getRevenueStats(
            @RequestParam(required = false, defaultValue = "30") int days) {
        Map<String, Object> stats = adminService.getRevenueStats(days);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/stats/orders")
    public ResponseEntity<Map<String, Object>> getOrderStats(
            @RequestParam(required = false, defaultValue = "30") int days) {
        Map<String, Object> stats = adminService.getOrderStats(days);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            Pageable pageable) {
        Map<String, Object> users = adminService.getUsers(search, role, pageable);
        return ResponseEntity.ok(users);
    }

    @PutMapping("/users/{userId}/balance")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> adjustUserBalance(
            @PathVariable Long userId, @Valid @RequestBody BalanceAdjustmentRequest request) {
        adminService.adjustUserBalance(userId, request.getAmount(), request.getReason());
        adminAuditService.recordWithAmount(
                "user.balance_adjust",
                "USER",
                userId,
                "User #" + userId,
                request.getReason() == null || request.getReason().isBlank()
                        ? "Manual balance adjustment"
                        : request.getReason(),
                request.getAmount());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long userId, @RequestBody Map<String, String> request) {
        adminService.updateUserRole(userId, request.get("role"));
        adminAuditService.record(
                "user.role_change",
                "USER",
                userId,
                "User #" + userId,
                "Set role to " + request.get("role"));
        return ResponseEntity.noContent().build();
    }

    /**
     * Recent admin-action feed. Powers the "Recent admin actions" sidebar on the dashboard.
     * Replaces the previous in-memory Zustand store which was wiped on page refresh.
     */
    @GetMapping("/audit-log")
    public ResponseEntity<List<Map<String, Object>>> getAuditLog(
            @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        var page =
                adminAuditLogRepository.findAllByOrderByCreatedAtDesc(
                        org.springframework.data.domain.PageRequest.of(0, safeLimit));
        List<Map<String, Object>> items =
                page.getContent().stream()
                        .map(
                                row -> {
                                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                                    m.put("id", row.getId());
                                    m.put("actor", row.getAdminUsername());
                                    m.put("action", row.getAction());
                                    Long tid = row.getTargetId();
                                    m.put(
                                            "target",
                                            row.getTargetType().toLowerCase()
                                                    + (tid == null ? "" : ":" + tid));
                                    m.put("targetLabel", row.getTargetLabel());
                                    m.put("summary", row.getSummary());
                                    if (row.getAmount() != null) m.put("amount", row.getAmount());
                                    m.put("createdAt", row.getCreatedAt());
                                    return m;
                                })
                        .toList();
        return ResponseEntity.ok(items);
    }

    /**
     * Republish Kafka events for stuck orders Useful for orders that failed to process due to
     * transient errors
     */
    @PostMapping("/orders/{orderId}/republish-event")
    public ResponseEntity<Map<String, Object>> republishOrderEvent(@PathVariable Long orderId) {
        // Fetch order with all relationships eagerly loaded
        com.smmpanel.entity.Order order =
                orderRepository
                        .findByIdWithAllDetails(orderId)
                        .orElseThrow(
                                () ->
                                        new com.smmpanel.exception.ResourceNotFoundException(
                                                "Order not found: " + orderId));

        // Create and publish OrderCreatedEvent
        com.smmpanel.event.OrderCreatedEvent event =
                new com.smmpanel.event.OrderCreatedEvent(
                        this, order.getId(), order.getUser().getId());
        event.setServiceId(order.getService().getId());
        event.setQuantity(order.getQuantity());
        event.setTimestamp(java.time.LocalDateTime.now());
        event.setCreatedAt(order.getCreatedAt());

        orderEventProducer.publishOrderCreatedEvent(event);

        return ResponseEntity.ok(
                Map.of(
                        "success",
                        true,
                        "message",
                        "Kafka event republished for order " + orderId,
                        "orderId",
                        orderId,
                        "serviceId",
                        order.getService().getId()));
    }
}
