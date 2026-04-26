package com.smmpanel.controller;

import com.smmpanel.dto.support.AddMessageRequest;
import com.smmpanel.dto.support.CreateTicketRequest;
import com.smmpanel.dto.support.TicketMessageResponse;
import com.smmpanel.dto.support.TicketResponse;
import com.smmpanel.service.support.SupportService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

/** User-facing support tickets API under {@code /api/v1/tickets}. */
@Slf4j
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    @GetMapping
    public ResponseEntity<List<TicketResponse>> listMyTickets() {
        return ResponseEntity.ok(supportService.listMyTickets());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getTicket(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(supportService.getMyTicket(id));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(404).build();
        }
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody CreateTicketRequest request) {
        return ResponseEntity.ok(supportService.createTicket(request));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<TicketMessageResponse> addMessage(
            @PathVariable Long id, @Valid @RequestBody AddMessageRequest request) {
        try {
            return ResponseEntity.ok(supportService.addMessage(id, request));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(404).build();
        } catch (IllegalStateException e) {
            log.info("Ticket message rejected: {}", e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }
}
