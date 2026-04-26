package com.smmpanel.controller;

import com.smmpanel.dto.support.AddMessageRequest;
import com.smmpanel.dto.support.TicketMessageResponse;
import com.smmpanel.dto.support.TicketResponse;
import com.smmpanel.entity.SupportTicket;
import com.smmpanel.service.support.SupportService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-side support ticket operations under {@code /api/v2/admin/tickets}. Lives on the {@code
 * /admin} path so it inherits {@code hasRole('ADMIN')} from {@link
 * com.smmpanel.config.SecurityConfig}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/admin/tickets")
@RequiredArgsConstructor
public class AdminSupportController {

    private final SupportService supportService;

    /** Paginated list, optionally filtered by status. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "status", required = false) String status, Pageable pageable) {
        SupportTicket.Status parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = SupportTicket.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "Unknown status: " + status));
            }
        }
        Page<TicketResponse> page = supportService.adminListTickets(parsed, pageable);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tickets", page.getContent());
        body.put("totalPages", page.getTotalPages());
        body.put("totalElements", page.getTotalElements());
        body.put("currentPage", page.getNumber());
        body.put("pageSize", page.getSize());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(supportService.adminGetTicket(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<TicketMessageResponse> reply(
            @PathVariable Long id, @Valid @RequestBody AddMessageRequest request) {
        try {
            return ResponseEntity.ok(supportService.adminAddMessage(id, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.info("Admin reply rejected on ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<TicketResponse> setStatus(
            @PathVariable Long id, @RequestBody Map<String, String> request) {
        String raw = request == null ? null : request.get("status");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        SupportTicket.Status parsed;
        try {
            parsed = SupportTicket.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(supportService.adminSetStatus(id, parsed));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
