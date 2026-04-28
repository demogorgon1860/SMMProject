package com.smmpanel.controller;

import com.smmpanel.dto.refill.RefillRequestResponse;
import com.smmpanel.dto.refill.RejectRefillRequestBody;
import com.smmpanel.entity.RefillRequest;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.service.refill.RefillRequestService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin queue for user-initiated refill requests. Lives under {@code /api/v2/admin/refill-requests}
 * so the URL-level {@code hasRole('ADMIN')} matcher in {@code SecurityConfig} applies.
 *
 * <p>All endpoints are role-gated by URL prefix; no per-method {@code @PreAuthorize} is needed.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/refill-requests")
@RequiredArgsConstructor
public class AdminRefillRequestController {

    private final RefillRequestService refillRequestService;
    private final com.smmpanel.service.admin.AdminAuditService adminAuditService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "status", required = false) String status, Pageable pageable) {
        RefillRequest.Status parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = RefillRequest.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Unknown status: " + status));
            }
        }
        Page<RefillRequestResponse> page = refillRequestService.adminList(parsed, pageable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.getContent());
        body.put("totalPages", page.getTotalPages());
        body.put("totalElements", page.getTotalElements());
        body.put("currentPage", page.getNumber());
        body.put("pageSize", page.getSize());
        body.put("pendingCount", refillRequestService.countPending());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RefillRequestResponse> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(refillRequestService.adminGet(id));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id) {
        try {
            RefillRequestResponse resp = refillRequestService.approve(id);
            adminAuditService.record(
                    "refill.approve",
                    "REFILL_REQUEST",
                    id,
                    "Refill #" + id,
                    "Approved refill on order #"
                            + resp.getOrderId()
                            + (resp.getRefillOrderId() != null
                                    ? " → refill order #" + resp.getRefillOrderId()
                                    : ""));
            return ResponseEntity.ok(resp);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.info("Approve refill request {} rejected: {}", id, e.getMessage());
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (ApiException e) {
            // OrderRefillService throws ApiException for ineligibility / view-count errors.
            log.info("Approve refill request {} failed via service: {}", id, e.getMessage());
            return ResponseEntity.status(e.getStatus().value())
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable Long id, @Valid @RequestBody RejectRefillRequestBody body) {
        try {
            RefillRequestResponse resp = refillRequestService.reject(id, body.getReason());
            adminAuditService.record(
                    "refill.reject",
                    "REFILL_REQUEST",
                    id,
                    "Refill #" + id,
                    "Rejected refill on order #" + resp.getOrderId() + " · " + body.getReason());
            return ResponseEntity.ok(resp);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
