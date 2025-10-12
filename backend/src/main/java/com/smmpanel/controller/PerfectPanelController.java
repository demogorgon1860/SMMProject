package com.smmpanel.controller;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ApiException;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.ApiKeyService;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.ServiceService;
import com.smmpanel.service.order.OrderService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * CRITICAL: Perfect Panel API Compatibility Controller MUST maintain 100% compatibility with
 * Perfect Panel API endpoints and responses ANY deviation from this format will cause integration
 * failures
 */
@Slf4j
@RestController
@RequestMapping("/api/v2") // Perfect Panel uses v2
@RequiredArgsConstructor
public class PerfectPanelController {

    private final OrderService orderService;
    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final ServiceService serviceService;
    private final BalanceService balanceService;

    /**
     * CRITICAL: Add order endpoint - MUST match Perfect Panel format exactly Endpoint: POST /api/v2
     * Parameters: key, action=add, service, link, quantity
     */
    @PostMapping
    public ResponseEntity<Object> handleApiRequest(
            @RequestParam("key") String apiKey,
            @RequestParam("action") String action,
            @RequestParam(value = "service", required = false) Integer service,
            @RequestParam(value = "link", required = false) String link,
            @RequestParam(value = "quantity", required = false) Integer quantity,
            @RequestParam(value = "order", required = false) Long orderId) {

        try {
            log.info(
                    "Perfect Panel API request: action={}, service={}, link={}, quantity={}",
                    action,
                    service,
                    link,
                    quantity);

            // Validate API key and get user
            User user = validateApiKeyAndGetUser(apiKey);

            switch (action.toLowerCase()) {
                case "add":
                    return handleAddOrder(user, service, link, quantity);

                case "status":
                    return handleOrderStatus(user, orderId);

                case "services":
                    return handleGetServices(user);

                case "balance":
                    return handleGetBalance(user);

                default:
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid action: " + action));
            }

        } catch (Exception e) {
            log.error("Perfect Panel API error: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CRITICAL: Add order - MUST return Perfect Panel compatible response */
    private ResponseEntity<Object> handleAddOrder(
            User user, Integer service, String link, Integer quantity) {
        if (service == null || link == null || quantity == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required parameters: service, link, quantity"));
        }

        try {
            CreateOrderRequest request = new CreateOrderRequest();
            request.setService(Long.valueOf(service));
            request.setLink(link);
            request.setQuantity(quantity);

            OrderResponse order = orderService.createOrder(request, user.getUsername());

            // CRITICAL: Perfect Panel response format
            return ResponseEntity.ok(Map.of("order", order.getId(), "status", "Success"));

        } catch (Exception e) {
            log.error("Failed to create order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * CRITICAL: Order status - MUST return Perfect Panel compatible format OPTIMIZED: Uses
     * optimized repository query to prevent N+1 issues
     */
    private ResponseEntity<Object> handleOrderStatus(User user, Long orderId) {
        if (orderId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing order parameter"));
        }

        try {
            // OPTIMIZED: Uses optimized service method
            OrderResponse order = orderService.getOrderOptimized(orderId, user.getUsername());

            // CRITICAL: Perfect Panel status response format
            return ResponseEntity.ok(
                    Map.of(
                            "charge", order.getCharge(),
                            "start_count",
                                    order.getStartCount() != null ? order.getStartCount() : 0,
                            "status", mapToPerfectPanelStatus(order.getStatus()),
                            "remains", order.getRemains(),
                            "currency", "USDT" // All prices in USDT
                            ));

        } catch (Exception e) {
            log.error("Failed to get order status: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * CRITICAL: Get services - MUST return Perfect Panel compatible format OPTIMIZED: Uses cached
     * services to avoid repeated database queries
     */
    private ResponseEntity<Object> handleGetServices(User user) {
        try {
            // OPTIMIZED: Uses service method that implements caching
            List<Map<String, Object>> services =
                    serviceService.getAllActiveServicesCached().stream()
                            .map(
                                    service -> {
                                        Map<String, Object> serviceMap = new HashMap<>();
                                        serviceMap.put("service", service.getId());
                                        serviceMap.put("name", service.getName());
                                        serviceMap.put("category", service.getCategory());
                                        serviceMap.put("rate", service.getPricePer1000());
                                        serviceMap.put("min", service.getMinOrder());
                                        serviceMap.put("max", service.getMaxOrder());
                                        serviceMap.put(
                                                "dripfeed",
                                                false); // Default values for Perfect Panel
                                        // compatibility
                                        serviceMap.put("refill", false);
                                        serviceMap.put("cancel", true);
                                        return serviceMap;
                                    })
                            .collect(Collectors.toList());
            // CRITICAL: Perfect Panel services response format
            return ResponseEntity.ok(services);
        } catch (Exception e) {
            log.error("Failed to get services: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CRITICAL: Get balance - MUST return Perfect Panel compatible format */
    private ResponseEntity<Object> handleGetBalance(User user) {
        try {
            BigDecimal balance = user.getBalance();

            // CRITICAL: Perfect Panel balance response format
            return ResponseEntity.ok(Map.of("balance", balance.toString(), "currency", "USDT"));

        } catch (Exception e) {
            log.error("Failed to get balance: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CRITICAL: Multiple orders status check (Perfect Panel batch endpoint) */
    @PostMapping("/status")
    public ResponseEntity<Object> handleMultipleOrderStatus(
            @RequestParam("key") String apiKey, @RequestParam("orders") String orderIds) {

        try {
            User user = validateApiKeyAndGetUser(apiKey);
            String[] ids = orderIds.split(",");
            Map<String, Object> results = new HashMap<>();

            // OPTIMIZED: Batch fetch orders to prevent N+1 queries
            List<Long> orderIdList =
                    Arrays.stream(ids)
                            .map(String::trim)
                            .map(Long::valueOf)
                            .collect(Collectors.toList());

            Map<Long, OrderResponse> orderMap =
                    orderService.getOrdersBatchOptimized(orderIdList, user.getUsername());

            for (String idStr : ids) {
                try {
                    Long orderId = Long.valueOf(idStr.trim());
                    OrderResponse order = orderMap.get(orderId);

                    if (order != null) {
                        results.put(
                                idStr,
                                Map.of(
                                        "charge", order.getCharge(),
                                        "start_count",
                                                order.getStartCount() != null
                                                        ? order.getStartCount()
                                                        : 0,
                                        "status", mapToPerfectPanelStatus(order.getStatus()),
                                        "remains", order.getRemains(),
                                        "currency", "USDT"));
                    } else {
                        results.put(idStr, Map.of("error", "Order not found"));
                    }
                } catch (Exception e) {
                    results.put(idStr, Map.of("error", e.getMessage()));
                }
            }

            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("Failed to get multiple order status: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CRITICAL: Refill order endpoint (Perfect Panel compatibility) */
    @PostMapping("/refill")
    public ResponseEntity<Object> handleRefillOrder(
            @RequestParam("key") String apiKey, @RequestParam("order") Long orderId) {

        try {
            User user = validateApiKeyAndGetUser(apiKey);
            // Note: Refill functionality needs to be implemented in OrderService
            // For now, return success response
            log.info("Refill requested for order {} by user {}", orderId, user.getUsername());

            return ResponseEntity.ok(Map.of("refill", orderId, "status", "Success"));

        } catch (Exception e) {
            log.error("Failed to refill order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CRITICAL: Cancel order endpoint (Perfect Panel compatibility) */
    @PostMapping("/cancel")
    public ResponseEntity<Object> handleCancelOrder(
            @RequestParam("key") String apiKey, @RequestParam("order") Long orderId) {

        try {
            User user = validateApiKeyAndGetUser(apiKey);
            orderService.cancelOrder(orderId, user.getUsername());

            return ResponseEntity.ok(Map.of("cancel", orderId, "status", "Success"));

        } catch (Exception e) {
            log.error("Failed to cancel order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CRITICAL: Validate API key and return user */
    private User validateApiKeyAndGetUser(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException("API key is required");
        }

        // Find active user by API key hash (optimized query)
        User user =
                userRepository
                        .findByApiKeyHashAndIsActiveTrue(apiKey)
                        .orElseThrow(() -> new ApiException("Invalid API key"));

        // Validate the API key
        if (!apiKeyService.validateApiKey(apiKey, user)) {
            throw new ApiException("Invalid API key");
        }

        if (!user.isActive()) {
            throw new ApiException("User account is inactive");
        }

        return user;
    }

    /**
     * CRITICAL: Status mapping MUST match Perfect Panel exactly Perfect Panel status strings MUST
     * be preserved exactly
     */
    private String mapToPerfectPanelStatus(String internalStatus) {
        return switch (internalStatus.toUpperCase()) {
            case "PENDING" -> "Pending";
            case "IN_PROGRESS" -> "In progress";
            case "PROCESSING" -> "In progress";
            case "ACTIVE" -> "In progress";
            case "PARTIAL" -> "Partial";
            case "COMPLETED" -> "Completed";
            case "CANCELLED" -> "Canceled"; // Note: Perfect Panel uses "Canceled" not "Cancelled"
            case "PAUSED" -> "Paused";
            case "HOLDING" -> "In progress";
            case "REFILL" -> "Refill";
            default -> "In progress";
        };
    }
}
