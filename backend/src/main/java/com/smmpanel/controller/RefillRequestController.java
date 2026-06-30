package com.smmpanel.controller;

import com.smmpanel.dto.refill.CreateRefillRequestBody;
import com.smmpanel.dto.refill.RefillBatchRequest;
import com.smmpanel.dto.refill.RefillBatchResponse;
import com.smmpanel.dto.refill.RefillRequestResponse;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.service.refill.RefillRequestService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing refill request endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/refill/requests} — submit many orders at once (the Refill page)
 *   <li>{@code POST /api/v1/orders/{orderId}/refill} — submit one order (the Orders drawer)
 *   <li>{@code GET /api/v1/orders/{orderId}/refill} — current request status for one order
 *   <li>{@code GET /api/v1/me/refill-requests} — full list of my requests
 * </ul>
 *
 * <p>Submitting only kicks off the automatic drop-check (request is born {@code CHECKING}); the
 * panel later promotes it to {@code PENDING} for admin approval, sized to the real dropped amount.
 *
 * <p>All paths sit under {@code /api/v1/orders/**} or {@code /api/v1/me/**} so the existing
 * SecurityConfig {@code .authenticated()} rule applies — no per-endpoint role gates needed.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RefillRequestController {

    private final RefillRequestService refillRequestService;

    /**
     * Batch submit — the Refill page paste-list ("29931, 29932, …"). Each order is auto-checked
     * independently; the response carries a per-order outcome so the page can show row-by-row
     * results. Always 200 (the batch as a whole never fails — individual items carry their own
     * accepted/rejected flag).
     */
    @PostMapping("/refill/requests")
    public ResponseEntity<RefillBatchResponse> submitBatch(
            @Valid @RequestBody RefillBatchRequest body) {
        return ResponseEntity.ok(
                refillRequestService.createRequests(body.getOrderIds(), body.getNote()));
    }

    @PostMapping("/orders/{orderId}/refill")
    public ResponseEntity<?> requestRefill(
            @PathVariable Long orderId,
            @Valid @RequestBody(required = false) CreateRefillRequestBody body) {
        try {
            String note = body == null ? null : body.getNote();
            RefillRequestResponse resp = refillRequestService.createRequest(orderId, note);
            return ResponseEntity.ok(resp);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(401).build();
        } catch (IllegalStateException e) {
            // 409 = "you can't do this in the current state". Includes ineligible status, expired
            // window, already-approved.
            log.info("Refill request rejected for order {}: {}", orderId, e.getMessage());
            return ResponseEntity.status(409).body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/orders/{orderId}/refill")
    public ResponseEntity<RefillRequestResponse> getRefillForOrder(@PathVariable Long orderId) {
        return refillRequestService
                .getMyForOrder(orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/me/refill-requests")
    public ResponseEntity<List<RefillRequestResponse>> listMine() {
        return ResponseEntity.ok(refillRequestService.listMy());
    }
}
