package com.smmpanel.controller;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // Perfect Panel compatible endpoints
    @PostMapping("/orders")
    public ResponseEntity<PerfectPanelResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse order = orderService.createOrder(request);
        return ResponseEntity.ok(PerfectPanelResponse.success(order));
    }

    @GetMapping("/orders")
    public ResponseEntity<PerfectPanelResponse> getOrders(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        Page<OrderResponse> orders = orderService.getUserOrders(status, pageable);
        return ResponseEntity.ok(PerfectPanelResponse.success(orders));
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<PerfectPanelResponse> getOrder(@PathVariable Long id) {
        OrderResponse order = orderService.getOrder(id);
        return ResponseEntity.ok(PerfectPanelResponse.success(order));
    }
}