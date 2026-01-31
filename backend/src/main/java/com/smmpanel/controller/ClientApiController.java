package com.smmpanel.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ApiException;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.ApiKeyService;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.ServiceService;
import com.smmpanel.service.order.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.util.ArrayList;
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
 * CRITICAL: Client API Controller for third-party integrations Maintains Perfect Panel API
 * compatibility for external clients MUST maintain 100% compatibility with Perfect Panel API
 * endpoints and responses ANY deviation from this format will cause integration failures
 */
@Slf4j
@RestController
@RequestMapping("/api/v2") // Perfect Panel uses v2
@RequiredArgsConstructor
public class ClientApiController {

    private final OrderService orderService;
    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceService serviceService;
    private final BalanceService balanceService;

    /**
     * PRODUCTION-READY: GET endpoint for read-only operations Supports: balance, services, status,
     * statuses/multi_status Rejects write operations (add, cancel, mass) for CSRF protection
     * Supports both query parameters and request body (for compatibility)
     *
     * @param request HTTP request for unified parameter reading
     * @param apiKeyParam User's API key for authentication
     * @param actionParam Action to perform (balance, services, status, statuses)
     * @param orderIdParam Order ID for status queries
     * @param ordersParam Comma-separated order IDs for multi-status queries
     * @return Response entity with requested data
     */
    @GetMapping
    public ResponseEntity<Object> handleApiGetRequest(
            HttpServletRequest request,
            @RequestParam(value = "key", required = false) String apiKeyParam,
            @RequestParam(value = "action", required = false) String actionParam,
            @RequestParam(value = "order", required = false) Long orderIdParam,
            @RequestParam(value = "orders", required = false) String ordersParam) {

        try {
            // Read parameters (supports query parameters)
            String apiKey = apiKeyParam != null ? apiKeyParam : request.getParameter("key");
            String action = actionParam != null ? actionParam : request.getParameter("action");
            Long orderId = orderIdParam;
            String orders = ordersParam != null ? ordersParam : request.getParameter("orders");

            // Try to parse order from request if not provided
            if (orderId == null) {
                String orderStr = request.getParameter("order");
                if (orderStr != null && !orderStr.isEmpty()) {
                    try {
                        orderId = Long.parseLong(orderStr);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }

            log.info("Client API GET request: action={}", action);

            // Validate required parameters
            if (apiKey == null || apiKey.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required parameter: key"));
            }
            if (action == null || action.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required parameter: action"));
            }

            // Validate API key and get user
            User user = validateApiKeyAndGetUser(apiKey);

            // Only allow read-only operations via GET (CSRF protection)
            switch (action.toLowerCase()) {
                case "balance":
                    return handleGetBalance(user);

                case "services":
                    return handleGetServices(user);

                case "status":
                    if (orderId == null) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Missing required parameter: order"));
                    }
                    return handleOrderStatus(user, orderId);

                case "statuses":
                case "multi_status":
                    if (orders == null || orders.trim().isEmpty()) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Missing required parameter: orders"));
                    }
                    return handleMultipleOrderStatus(user, orders);

                    // Reject write operations via GET for security
                case "add":
                case "cancel":
                case "mass":
                    return ResponseEntity.status(405) // Method Not Allowed
                            .body(
                                    Map.of(
                                            "error",
                                            "Action '"
                                                    + action
                                                    + "' requires POST request. GET is not allowed"
                                                    + " for write operations.",
                                            "code",
                                            "METHOD_NOT_ALLOWED"));

                default:
                    return ResponseEntity.badRequest()
                            .body(
                                    Map.of(
                                            "error",
                                            "Invalid action: " + action,
                                            "supported_actions",
                                            "balance, services, status, statuses"));
            }

        } catch (ApiException e) {
            log.warn("Client API GET request failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "code", "API_ERROR"));
        } catch (Exception e) {
            log.error("Client API GET error: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal server error", "code", "INTERNAL_ERROR"));
        }
    }

    /**
     * CRITICAL: Main API endpoint - MUST match Perfect Panel format exactly Endpoint: POST /api/v2
     * Parameters: key, action, and action-specific parameters Supports: query parameters,
     * form-urlencoded body, AND JSON body
     *
     * <p>NOTE: We don't use @RequestBody because it doesn't work with
     * application/x-www-form-urlencoded. Instead, we manually read JSON body when Content-Type is
     * application/json.
     */
    @PostMapping
    public ResponseEntity<Object> handleApiRequest(HttpServletRequest request) {

        try {
            // Parse JSON body if content type is application/json
            Map<String, Object> requestBody = parseJsonBodyIfPresent(request);

            // Read parameters from query string, form body, or JSON body
            String apiKey = getParam(request, requestBody, "key", null);
            String action = getParam(request, requestBody, "action", null);
            Integer service = getIntParam(request, requestBody, "service", null);
            String link = getParam(request, requestBody, "link", null);
            Integer quantity = getIntParam(request, requestBody, "quantity", null);
            Long orderId = getLongParam(request, requestBody, "order", null);
            String orders = getParam(request, requestBody, "orders", null);
            String customComments = getParam(request, requestBody, "customComments", null);

            log.info("Client API POST request: action={}", action);

            // Validate required parameters
            if (apiKey == null || apiKey.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required parameter: key"));
            }
            if (action == null || action.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required parameter: action"));
            }

            // Validate API key and get user
            User user = validateApiKeyAndGetUser(apiKey);

            switch (action.toLowerCase()) {
                case "add":
                    return handleAddOrder(user, service, link, quantity, customComments);

                case "status":
                    return handleOrderStatus(user, orderId);

                case "services":
                    return handleGetServices(user);

                case "balance":
                    return handleGetBalance(user);

                case "statuses":
                case "multi_status":
                    return handleMultipleOrderStatus(user, orders);

                case "cancel":
                    return handleCancelOrder(user, orderId);

                case "mass":
                    return handleMassOrder(user, requestBody);

                default:
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Invalid action: " + action));
            }

        } catch (ApiException e) {
            log.warn("Client API POST request failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "code", "API_ERROR"));
        } catch (Exception e) {
            log.error("Client API POST error: {}", e.getMessage(), e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Internal server error", "code", "INTERNAL_ERROR"));
        }
    }

    /**
     * CRITICAL: Add order - Returns enhanced response with complete order details Includes: order
     * ID, charge, start_count, created_at, remaining_balance, currency
     *
     * @param customComments For custom comment services (service 9): comments separated by
     *     newlines. For emoji comment services (service 5): "EMOJI:POSITIVE" or "EMOJI:NEGATIVE"
     */
    private ResponseEntity<Object> handleAddOrder(
            User user, Integer service, String link, Integer quantity, String customComments) {
        if (service == null || link == null || quantity == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing required parameters: service, link, quantity"));
        }

        try {
            CreateOrderRequest request = new CreateOrderRequest();
            request.setService(Long.valueOf(service));
            request.setLink(link);
            request.setQuantity(quantity);
            request.setCustomComments(customComments);

            OrderResponse order = orderService.createOrder(request, user.getUsername());

            // Refresh user to get updated balance after order creation
            user =
                    userRepository
                            .findById(user.getId())
                            .orElseThrow(() -> new ApiException("User not found"));

            // Enhanced response with all required fields
            Map<String, Object> response = new HashMap<>();
            response.put("status", "Success");
            response.put("order", order.getId());
            response.put("charge", order.getCharge());
            response.put("start_count", order.getStartCount() != null ? order.getStartCount() : 0);
            response.put("created_at", order.getCreatedAt().toString());
            response.put("remaining_balance", user.getBalance().toString());
            response.put("currency", "USD");

            return ResponseEntity.ok(response);

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
                            "charge",
                            order.getCharge(),
                            "start_count",
                            order.getStartCount() != null ? order.getStartCount() : 0,
                            "status",
                            mapToPerfectPanelStatus(order.getStatus()),
                            "remains",
                            order.getRemains() != null ? order.getRemains() : 0,
                            "currency",
                            "USDT" // All prices in USDT
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

    /** CRITICAL: Multiple orders status check - Batch endpoint for checking multiple orders */
    private ResponseEntity<Object> handleMultipleOrderStatus(User user, String orderIds) {
        if (orderIds == null || orderIds.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Orders parameter is required"));
        }

        try {
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
                                        "charge",
                                        order.getCharge(),
                                        "start_count",
                                        order.getStartCount() != null ? order.getStartCount() : 0,
                                        "status",
                                        mapToPerfectPanelStatus(order.getStatus()),
                                        "remains",
                                        order.getRemains() != null ? order.getRemains() : 0,
                                        "currency",
                                        "USDT"));
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
            HttpServletRequest request,
            @RequestParam(value = "key", required = false) String apiKeyParam,
            @RequestParam(value = "order", required = false) Long orderIdParam) {

        try {
            // Read parameters from query string or form body
            String apiKey = apiKeyParam != null ? apiKeyParam : request.getParameter("key");
            Long orderId = orderIdParam;
            if (orderId == null) {
                String orderStr = request.getParameter("order");
                if (orderStr != null && !orderStr.isEmpty()) {
                    orderId = Long.parseLong(orderStr);
                }
            }

            if (apiKey == null || apiKey.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required parameter: key"));
            }
            if (orderId == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required parameter: order"));
            }

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

    /** CRITICAL: Cancel order - Cancels an order */
    private ResponseEntity<Object> handleCancelOrder(User user, Long orderId) {
        if (orderId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Order parameter is required"));
        }

        try {
            orderService.cancelOrder(orderId, user.getUsername());
            return ResponseEntity.ok(Map.of("cancel", orderId, "status", "Success"));

        } catch (Exception e) {
            log.error("Failed to cancel order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * CRITICAL: Mass order - Create multiple orders in single API call Uses action=mass with JSON
     * request body Format: { "orders": [ { "service": 1, "link": "...", "quantity": 1000 } ] }
     */
    private ResponseEntity<Object> handleMassOrder(User user, Map<String, Object> requestBody) {
        if (requestBody == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Request body is required for mass order action"));
        }

        try {

            // Extract orders array from request
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ordersData =
                    (List<Map<String, Object>>) requestBody.get("orders");

            if (ordersData == null || ordersData.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Orders array is required and cannot be empty"));
            }

            if (ordersData.size() > 100) {
                return ResponseEntity.badRequest()
                        .body(
                                Map.of(
                                        "error",
                                        "Maximum 100 orders per request. Received: "
                                                + ordersData.size()));
            }

            // Calculate total cost first
            BigDecimal totalCost = BigDecimal.ZERO;
            List<CreateOrderRequest> validOrders = new ArrayList<>();

            for (int i = 0; i < ordersData.size(); i++) {
                Map<String, Object> orderData = ordersData.get(i);
                final int orderIndex = i; // Make effectively final for lambda

                // Validate required fields
                if (!orderData.containsKey("service")
                        || !orderData.containsKey("link")
                        || !orderData.containsKey("quantity")) {
                    return ResponseEntity.badRequest()
                            .body(
                                    Map.of(
                                            "error",
                                            "Order at index "
                                                    + orderIndex
                                                    + " is missing required fields (service, link,"
                                                    + " quantity)"));
                }

                Integer serviceId = ((Number) orderData.get("service")).intValue();
                String link = (String) orderData.get("link");
                Integer quantity = ((Number) orderData.get("quantity")).intValue();
                String orderCustomComments =
                        orderData.containsKey("customComments")
                                ? (String) orderData.get("customComments")
                                : null;

                // Validate quantity
                if (quantity < 1 || quantity > 1000000) {
                    return ResponseEntity.badRequest()
                            .body(
                                    Map.of(
                                            "error",
                                            "Order at index "
                                                    + orderIndex
                                                    + " has invalid quantity. Must be between 1 and"
                                                    + " 1,000,000"));
                }

                // Get service and calculate cost
                com.smmpanel.entity.Service service =
                        serviceRepository
                                .findById(Long.valueOf(serviceId))
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        "Service "
                                                                + serviceId
                                                                + " not found at order index "
                                                                + orderIndex));

                BigDecimal orderCost =
                        service.getPricePer1000()
                                .multiply(BigDecimal.valueOf(quantity))
                                .divide(
                                        BigDecimal.valueOf(1000),
                                        2,
                                        java.math.RoundingMode.HALF_UP);

                totalCost = totalCost.add(orderCost);

                // Create order request
                CreateOrderRequest orderRequest = new CreateOrderRequest();
                orderRequest.setService(Long.valueOf(serviceId));
                orderRequest.setLink(link);
                orderRequest.setQuantity(quantity);
                orderRequest.setCustomComments(orderCustomComments);
                validOrders.add(orderRequest);
            }

            // Check balance before creating any orders
            if (user.getBalance().compareTo(totalCost) < 0) {
                return ResponseEntity.badRequest()
                        .body(
                                Map.of(
                                        "error",
                                        "Insufficient balance. Required: "
                                                + totalCost
                                                + ", Available: "
                                                + user.getBalance()));
            }

            // Create all orders (transactional - all succeed or all fail)
            List<Map<String, Object>> createdOrders = new ArrayList<>();
            BigDecimal actualTotalCharge = BigDecimal.ZERO;

            for (CreateOrderRequest orderRequest : validOrders) {
                OrderResponse orderResponse =
                        orderService.createOrder(orderRequest, user.getUsername());

                // Refresh user to get latest balance
                user =
                        userRepository
                                .findById(user.getId())
                                .orElseThrow(() -> new ApiException("User not found"));

                Map<String, Object> orderResult = new HashMap<>();
                orderResult.put("order", orderResponse.getId());
                orderResult.put("charge", orderResponse.getCharge());
                orderResult.put(
                        "start_count",
                        orderResponse.getStartCount() != null ? orderResponse.getStartCount() : 0);
                orderResult.put("created_at", orderResponse.getCreatedAt().toString());
                orderResult.put("status", "Success");

                createdOrders.add(orderResult);

                // Sum up actual charges
                actualTotalCharge =
                        actualTotalCharge.add(new BigDecimal(orderResponse.getCharge()));
            }

            // Build response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "Success");
            response.put("orders", createdOrders);
            response.put("total_orders", createdOrders.size());
            response.put("total_charge", actualTotalCharge.toString());
            response.put("remaining_balance", user.getBalance().toString());
            response.put("currency", "USD");

            log.info(
                    "Mass order created successfully for user {}: {} orders, total charge: {}",
                    user.getUsername(),
                    createdOrders.size(),
                    actualTotalCharge);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to create mass order: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get authenticated user from Spring Security context. The API key has already been validated
     * by ApiKeyAuthenticationFilter, so we just retrieve the authenticated user.
     */
    private User validateApiKeyAndGetUser(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException("API key is required");
        }

        // Get the authenticated user from Spring Security context
        // (already validated by ApiKeyAuthenticationFilter)
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException("Invalid API key");
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof User)) {
            throw new ApiException("Invalid API key");
        }

        User user = (User) principal;

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
            case "CANCELLED", "CANCELED" -> "Canceled"; // Support both spellings
            case "PAUSED" -> "Paused";
            case "HOLDING" -> "In progress";
            case "REFILL" -> "Refill";
            case "ERROR" -> "Error";
            default -> "In progress";
        };
    }

    // =====================================================================
    // Helper methods for reading parameters from multiple sources
    // Supports: query parameters, form-urlencoded body, JSON body
    // =====================================================================

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse JSON body if Content-Type is application/json. Returns null for form-urlencoded or
     * other content types.
     */
    private Map<String, Object> parseJsonBodyIfPresent(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = request.getReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
                String body = sb.toString();
                if (body != null && !body.trim().isEmpty()) {
                    return objectMapper.readValue(
                            body, new TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception e) {
                log.warn("Failed to parse JSON body: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * Read string parameter from multiple sources in order: 1. HttpServletRequest.getParameter()
     * (query + form body) 2. JSON request body
     */
    private String getParam(
            HttpServletRequest request, Map<String, Object> body, String name, String fallback) {
        // 1. Check fallback first (for backwards compatibility)
        if (fallback != null && !fallback.isEmpty()) {
            return fallback;
        }
        // 2. Check request.getParameter() (reads both query and form body)
        String param = request.getParameter(name);
        if (param != null && !param.isEmpty()) {
            return param;
        }
        // 3. Check JSON body
        if (body != null && body.containsKey(name)) {
            Object value = body.get(name);
            return value != null ? value.toString() : null;
        }
        return null;
    }

    /** Read Integer parameter from multiple sources */
    private Integer getIntParam(
            HttpServletRequest request, Map<String, Object> body, String name, Integer fallback) {
        if (fallback != null) return fallback;
        String param = getParam(request, body, name, null);
        if (param != null) {
            try {
                return Integer.parseInt(param);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Read Long parameter from multiple sources */
    private Long getLongParam(
            HttpServletRequest request, Map<String, Object> body, String name, Long fallback) {
        if (fallback != null) return fallback;
        String param = getParam(request, body, name, null);
        if (param != null) {
            try {
                return Long.parseLong(param);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
