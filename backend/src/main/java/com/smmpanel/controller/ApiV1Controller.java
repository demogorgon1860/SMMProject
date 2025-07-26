package com.smmpanel.controller;

import com.smmpanel.dto.*;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CRITICAL: Perfect Panel Compatible API Controller
 * MUST maintain exact API response formats for existing client compatibility
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApiV1Controller {

    private final OrderService orderService;
    private final ServiceService serviceService;
    private final BalanceService balanceService;
    private final UserService userService;

    @PostMapping("/order")
    public ResponseEntity<Map<String, Object>> createOrder(
            @RequestParam("service_id") Long serviceId,
            @RequestParam("link") String link,
            @RequestParam("quantity") Integer quantity,
            @RequestParam("api_key") String apiKey) {
        try {
            String username = userService.getUsernameByApiKey(apiKey);
            OrderCreateRequest request = OrderCreateRequest.builder()
                    .serviceId(serviceId)
                    .link(link)
                    .quantity(quantity)
                    .build();
            OrderResponse order = orderService.createOrder(request, username);
            Map<String, Object> response = new HashMap<>();
            response.put("order", order.getId());
            response.put("status", "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Order creation failed: {}", e.getMessage());
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            errorResponse.put("status", "error");
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getOrderStatus(
            @RequestParam("order_id") Long orderId,
            @RequestParam("api_key") String apiKey) {
        try {
            String username = userService.getUsernameByApiKey(apiKey);
            OrderResponse order = orderService.getOrder(orderId, username);
            Map<String, Object> response = new HashMap<>();
            response.put("charge", order.getCharge());
            response.put("start_count", order.getStartCount());
            response.put("status", mapStatusToPerfectPanel(order.getStatus()));
            response.put("remains", order.getRemains());
            response.put("currency", "USD");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/services")
    public ResponseEntity<List<Map<String, Object>>> getServices(@RequestParam("api_key") String apiKey) {
        try {
            userService.validateApiKey(apiKey);
            List<ServiceResponse> services = serviceService.getAllActiveServices();
            List<Map<String, Object>> response = services.stream()
                    .map(this::transformServiceToPerfectPanel)
                    .toList();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@RequestParam("api_key") String apiKey) {
        try {
            String username = userService.getUsernameByApiKey(apiKey);
            BigDecimal balance = balanceService.getUserBalanceByUsername(username);
            Map<String, Object> response = new HashMap<>();
            response.put("balance", balance);
            response.put("currency", "USD");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    private String mapStatusToPerfectPanel(OrderStatus status) {
        return switch (status) {
            case PENDING -> "Pending";
            case IN_PROGRESS -> "In progress";
            case PROCESSING -> "Processing";
            case ACTIVE -> "In progress";
            case PARTIAL -> "Partial";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Cancelled";
            case PAUSED -> "In progress";
            case HOLDING -> "In progress";
            case REFILL -> "In progress";
        };
    }

    private Map<String, Object> transformServiceToPerfectPanel(ServiceResponse service) {
        Map<String, Object> transformed = new HashMap<>();
        transformed.put("service", service.getId());
        transformed.put("name", service.getName());
        transformed.put("type", service.getCategory());
        transformed.put("rate", service.getPricePer1000());
        transformed.put("min", service.getMinOrder());
        transformed.put("max", service.getMaxOrder());
        transformed.put("category", service.getCategory());
        return transformed;
    }
} 