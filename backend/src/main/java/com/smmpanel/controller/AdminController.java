package com.smmpanel.controller;

import com.smmpanel.dto.admin.*;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.service.AdminService;
import com.smmpanel.service.OrderProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
public class AdminController {

    private final AdminService adminService;
    private final OrderProcessingService orderProcessingService;

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
        
        Map<String, Object> orders = adminService.getAllOrders(status, username, dateFrom, dateTo, pageable);
        return ResponseEntity.ok(orders);
    }

    @PostMapping("/orders/{orderId}/actions")
    public ResponseEntity<PerfectPanelResponse> performOrderAction(
            @PathVariable Long orderId,
            @Valid @RequestBody OrderActionRequest request) {
        
        switch (request.getAction().toLowerCase()) {
            case "stop":
                orderProcessingService.stopOrder(orderId, request.getReason());
                break;
            case "resume":
            case "start":
                orderProcessingService.resumeOrder(orderId);
                break;
            case "refill":
                orderProcessingService.refillOrder(orderId);
                break;
            case "cancel":
                adminService.cancelOrder(orderId, request.getReason());
                break;
            case "update_start_count":
                adminService.updateStartCount(orderId, request.getNewStartCount());
                break;
            case "complete":
                adminService.completeOrder(orderId);
                break;
            default:
                return ResponseEntity.badRequest()
                        .body(PerfectPanelResponse.error("Unknown action: " + request.getAction(), 400));
        }

        return ResponseEntity.ok(PerfectPanelResponse.builder()
                .order(Map.of("message", "Action completed successfully"))
                .build());
    }

    @PostMapping("/orders/bulk-actions")
    public ResponseEntity<PerfectPanelResponse> performBulkAction(
            @Valid @RequestBody BulkActionRequest request) {
        
        int processed = adminService.performBulkAction(request);
        
        return ResponseEntity.ok(PerfectPanelResponse.builder()
                .order(Map.of(
                        "message", "Bulk action completed",
                        "processed", processed
                ))
                .build());
    }

    @GetMapping("/traffic-sources")
    public ResponseEntity<List<TrafficSourceDto>> getTrafficSources() {
        List<TrafficSourceDto> sources = adminService.getTrafficSources();
        return ResponseEntity.ok(sources);
    }

    @PostMapping("/traffic-sources")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrafficSourceDto> createTrafficSource(
            @Valid @RequestBody TrafficSourceDto request) {
        TrafficSourceDto created = adminService.createTrafficSource(request);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/traffic-sources/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrafficSourceDto> updateTrafficSource(
            @PathVariable Long id,
            @Valid @RequestBody TrafficSourceDto request) {
        TrafficSourceDto updated = adminService.updateTrafficSource(id, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/traffic-sources/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteTrafficSource(@PathVariable Long id) {
        adminService.deleteTrafficSource(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversion-coefficients")
    public ResponseEntity<List<CoefficientDto>> getConversionCoefficients() {
        List<CoefficientDto> coefficients = adminService.getConversionCoefficients();
        return ResponseEntity.ok(coefficients);
    }

    @PutMapping("/conversion-coefficients/{serviceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CoefficientDto> updateConversionCoefficient(
            @PathVariable Long serviceId,
            @Valid @RequestBody CoefficientUpdateRequest request) {
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
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
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
        
        Map<String, Object> logs = adminService.getOperatorLogs(
                operatorUsername, action, dateFrom, dateTo, pageable);
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
            @PathVariable Long userId,
            @RequestBody Map<String, Object> request) {
        adminService.adjustUserBalance(
                userId, 
                (Double) request.get("amount"),
                (String) request.get("reason")
        );
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateUserRole(
            @PathVariable Long userId,
            @RequestBody Map<String, String> request) {
        adminService.updateUserRole(userId, request.get("role"));
        return ResponseEntity.noContent().build();
    }
}