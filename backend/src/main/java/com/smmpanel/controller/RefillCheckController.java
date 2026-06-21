package com.smmpanel.controller;

import com.smmpanel.dto.refill.RefillCheckResponse;
import com.smmpanel.exception.ApiException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.service.refill.RefillCheckService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing live drop-check endpoints for the Refill page.
 *
 * <ul>
 *   <li>{@code POST /api/v1/refill/check/{orderId}} — kick off a check (returns RUNNING
 *       immediately)
 *   <li>{@code GET /api/v1/refill/check/{orderId}} — latest check result (poll while RUNNING)
 * </ul>
 *
 * <p>Under {@code /api/v1/**} so the SecurityConfig {@code .authenticated()} rule applies;
 * ownership is enforced in the service (404, not 403).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RefillCheckController {

    private final RefillCheckService refillCheckService;

    @PostMapping("/refill/check/{orderId}")
    public ResponseEntity<?> startCheck(@PathVariable Long orderId) {
        try {
            return ResponseEntity.ok(refillCheckService.startCheckForUser(orderId));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(401).build();
        } catch (IllegalStateException e) {
            // ineligible status / combo service / rate limited
            log.info("Refill check rejected for order {}: {}", orderId, e.getMessage());
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        } catch (ApiException e) {
            // bot unavailable / refill checker disabled
            return ResponseEntity.status(e.getStatus().value())
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/refill/check/{orderId}")
    public ResponseEntity<RefillCheckResponse> getLatest(@PathVariable Long orderId) {
        try {
            return refillCheckService
                    .getLatestForUser(orderId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
