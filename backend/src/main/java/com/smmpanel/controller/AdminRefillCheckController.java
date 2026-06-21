package com.smmpanel.controller;

import com.smmpanel.dto.refill.RefillCheckResponse;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.service.admin.AdminAuditService;
import com.smmpanel.service.refill.RefillCheckService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin live drop-check tool — check ANY order by id (the admin Refill page). Role-gated by the
 * {@code /api/v2/admin/**} URL prefix in SecurityConfig.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/admin/refill-checks")
public class AdminRefillCheckController {

    private final RefillCheckService refillCheckService;
    private final AdminAuditService adminAuditService;

    @PostMapping("/{orderId}")
    public ResponseEntity<?> startCheck(@PathVariable Long orderId) {
        try {
            RefillCheckResponse resp = refillCheckService.startCheckForAdmin(orderId);
            adminAuditService.record(
                    "refill.check", "ORDER", orderId, "Order #" + orderId, "Started drop check");
            return ResponseEntity.ok(resp);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (ApiException e) {
            return ResponseEntity.status(e.getStatus().value())
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<RefillCheckResponse> getLatest(@PathVariable Long orderId) {
        return refillCheckService
                .getLatestForAdmin(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
