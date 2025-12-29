package com.smmpanel.controller;

import com.smmpanel.dto.admin.*;
import com.smmpanel.dto.refill.RefillResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.service.admin.AdminService;
import com.smmpanel.service.order.OrderService;
import com.smmpanel.service.refill.OrderRefillService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminController {

    private final AdminService adminService;
    private final OrderService orderService;
    private final OrderRefillService orderRefillService;
    private final com.smmpanel.producer.OrderEventProducer orderEventProducer;
    private final com.smmpanel.repository.jpa.OrderRepository orderRepository;

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardStats> getDashboardStats() {
        DashboardStats stats = adminService.getDashboardStats();
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> getAllOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            Pageable pageable) {

        Map<String, Object> orders =
                adminService.getAllOrders(status, username, dateFrom, dateTo, pageable);
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

        switch (request.getAction().toLowerCase()) {
            case "stop":
                adminService.pauseOrder(orderId, request.getReason());
                break;
            case "resume":
            case "start":
                adminService.resumeOrder(orderId);
                break;
            case "refill":
                adminService.refillOrder(orderId, request.getNewQuantity());
                break;
            case "cancel":
                adminService.cancelOrder(orderId, request.getReason());
                break;
            case "delete":
                adminService.deleteOrder(orderId);
                break;
            case "update_start_count":
                adminService.updateStartCount(orderId, request.getNewStartCount());
                break;
            case "complete":
                adminService.completeOrder(orderId);
                break;
            case "partial":
                adminService.markOrderAsPartial(orderId, request.getReason());
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
     * Create a refill for an order that has underdelivered views. Fetches current YouTube view
     * count and creates a new order for the remaining quantity.
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

    @GetMapping("/youtube-accounts")
    public ResponseEntity<Map<String, Object>> getYouTubeAccounts(Pageable pageable) {
        Map<String, Object> accounts = adminService.getYouTubeAccounts(pageable);
        return ResponseEntity.ok(accounts);
    }

    @PostMapping("/youtube-accounts/{id}/reset-daily-limit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> resetYouTubeAccountDailyLimit(@PathVariable Long id) {
        adminService.resetYouTubeAccountDailyLimit(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/youtube-accounts/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateYouTubeAccountStatus(
            @PathVariable Long id, @RequestBody Map<String, String> request) {
        adminService.updateYouTubeAccountStatus(id, request.get("status"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/system/health")
    public ResponseEntity<Map<String, Object>> getSystemHealth() {
        Map<String, Object> health = adminService.getSystemHealth();
        return ResponseEntity.ok(health);
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
            @PathVariable Long userId, @RequestBody Map<String, Object> request) {
        adminService.adjustUserBalance(
                userId, (Double) request.get("amount"), (String) request.get("reason"));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long userId, @RequestBody Map<String, String> request) {
        adminService.updateUserRole(userId, request.get("role"));
        return ResponseEntity.noContent().build();
    }

    // Test Service Endpoints for Frontend Testing

    @GetMapping("/binom/test")
    public ResponseEntity<Map<String, Object>> testBinomConnection() {
        return ResponseEntity.ok(adminService.testBinomConnection());
    }

    @PostMapping("/binom/sync")
    public ResponseEntity<Map<String, Object>> syncBinomCampaigns() {
        return ResponseEntity.ok(adminService.syncBinomCampaigns());
    }

    @PostMapping("/youtube/check-views")
    public ResponseEntity<Map<String, Object>> checkYouTubeViews(
            @RequestBody Map<String, String> request) {
        String videoUrl = request.get("videoUrl");
        return ResponseEntity.ok(adminService.checkYouTubeViews(videoUrl));
    }

    @PostMapping("/youtube/test-statistics")
    public ResponseEntity<Map<String, Object>> testYouTubeStatistics(
            @RequestBody Map<String, String> request) {
        String videoUrl = request.get("videoUrl");
        return ResponseEntity.ok(adminService.testYouTubeStatistics(videoUrl));
    }

    @PostMapping("/selenium/create-clip")
    public ResponseEntity<Map<String, Object>> createSeleniumClip(
            @RequestBody Map<String, Object> request) {
        String videoUrl = (String) request.get("videoUrl");
        Integer startTime = (Integer) request.get("startTime");
        Integer endTime = (Integer) request.get("endTime");
        return ResponseEntity.ok(adminService.createSeleniumClip(videoUrl, startTime, endTime));
    }

    @GetMapping("/selenium/clip/{jobId}/status")
    public ResponseEntity<Map<String, Object>> getClipStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(adminService.getClipJobStatus(jobId));
    }

    @GetMapping("/selenium/test-connection")
    public ResponseEntity<Map<String, Object>> testSeleniumConnection(
            @RequestParam(required = false) String testUrl) {
        return ResponseEntity.ok(adminService.testSeleniumConnection(testUrl));
    }

    @GetMapping("/selenium/accounts")
    public ResponseEntity<List<Map<String, Object>>> getYouTubeAccounts() {
        return ResponseEntity.ok(adminService.getAvailableYouTubeAccounts());
    }

    @PostMapping("/trigger-processing")
    public ResponseEntity<PerfectPanelResponse> triggerProcessing() {
        // Manually trigger pending order processing
        orderService.processPendingYouTubeOrders();
        return ResponseEntity.ok(
                PerfectPanelResponse.builder()
                        .data(Map.of("message", "Processing triggered"))
                        .success(true)
                        .build());
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
