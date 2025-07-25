package com.smmpanel.service;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.OrderValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * CRITICAL: Order Service with Perfect Panel compatibility methods
 * MUST maintain exact compatibility with Perfect Panel API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final BalanceService balanceService;
    private final YouTubeAutomationService youTubeAutomationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * CRITICAL: Create order with API key (Perfect Panel compatibility)
     */
    @Transactional
    public OrderResponse createOrderWithApiKey(CreateOrderRequest request, String apiKey) {
        try {
            log.info("Creating order with API key for service: {}, quantity: {}", request.getService(), request.getQuantity());

            // 1. Find user by API key
            User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                    .orElseThrow(() -> new OrderValidationException("Invalid API key"));

            // 2. Validate service
            com.smmpanel.entity.Service service = serviceRepository.findById(request.getService().longValue())
                    .orElseThrow(() -> new OrderValidationException("Service not found: " + request.getService()));

            if (!service.getActive()) {
                throw new OrderValidationException("Service is not active");
            }

            // 3. Validate quantity
            if (request.getQuantity() < service.getMinOrder() || request.getQuantity() > service.getMaxOrder()) {
                throw new OrderValidationException(
                    String.format("Quantity must be between %d and %d", service.getMinOrder(), service.getMaxOrder()));
            }

            // 4. Calculate charge
            BigDecimal charge = calculateCharge(service, request.getQuantity());

            // 5. Check balance
            if (user.getBalance().compareTo(charge) < 0) {
                throw new InsufficientBalanceException("Insufficient balance. Required: " + charge + ", Available: " + user.getBalance());
            }

            // 6. Create order
            Order order = new Order();
            order.setUser(user);
            order.setService(service);
            order.setLink(request.getLink());
            order.setQuantity(request.getQuantity());
            order.setCharge(charge);
            order.setStartCount(0);
            order.setRemains(request.getQuantity());
            order.setStatus(OrderStatus.PENDING);
            order.setProcessingPriority(0);
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(LocalDateTime.now());

            order = orderRepository.save(order);

            // 7. Deduct balance
            balanceService.deductBalance(user, charge, order, "Order payment for service " + service.getName());

            // 8. Send to processing queue
            kafkaTemplate.send("smm.youtube.processing", order.getId());

            log.info("Order {} created successfully for user {}", order.getId(), user.getUsername());

            return mapToOrderResponse(order);

        } catch (Exception e) {
            log.error("Failed to create order with API key: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * CRITICAL: Get order with API key (Perfect Panel compatibility)
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrderWithApiKey(Long orderId, String apiKey) {
        // Find user by API key
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        // Find order belonging to user
        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new OrderValidationException("Order not found"));

        return mapToOrderResponse(order);
    }

    /**
     * CRITICAL: Get services for API key (Perfect Panel format)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getServicesForApiKey(String apiKey) {
        // Validate API key
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        // Get active services
        List<com.smmpanel.entity.Service> services = serviceRepository.findByActiveOrderByIdAsc(true);

        // Convert to Perfect Panel format
        return services.stream()
                .map(this::mapToServiceMap)
                .collect(Collectors.toList());
    }

    /**
     * CRITICAL: Get balance for API key (Perfect Panel format)
     */
    @Transactional(readOnly = true)
    public String getBalanceForApiKey(String apiKey) {
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        return user.getBalance().toString();
    }

    /**
     * CRITICAL: Get multiple order status (Perfect Panel batch)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getMultipleOrderStatus(String apiKey, String[] orderIds) {
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        Map<String, Object> results = new HashMap<>();

        for (String orderIdStr : orderIds) {
            try {
                Long orderId = Long.parseLong(orderIdStr.trim());
                Order order = orderRepository.findByIdAndUser(orderId, user).orElse(null);
                
                if (order != null) {
                    Map<String, Object> orderStatus = new HashMap<>();
                    orderStatus.put("charge", order.getCharge().toString());
                    orderStatus.put("start_count", order.getStartCount());
                    orderStatus.put("status", mapToPerfectPanelStatus(order.getStatus()));
                    orderStatus.put("remains", order.getRemains());
                    orderStatus.put("currency", "USD");
                    
                    results.put(orderIdStr, orderStatus);
                } else {
                    results.put(orderIdStr, Map.of("error", "Order not found"));
                }
            } catch (NumberFormatException e) {
                results.put(orderIdStr, Map.of("error", "Invalid order ID"));
            }
        }

        return results;
    }

    /**
     * CRITICAL: Refill order with API key
     */
    @Transactional
    public void refillOrderWithApiKey(Long orderId, String apiKey) {
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new OrderValidationException("Order not found"));

        if (!order.getStatus().equals(OrderStatus.COMPLETED)) {
            throw new OrderValidationException("Order must be completed to refill");
        }

        // Change status to REFILL and send to processing
        order.setStatus(OrderStatus.REFILL);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        kafkaTemplate.send("smm.youtube.processing", order.getId());

        log.info("Order {} refill initiated by user {}", orderId, user.getUsername());
    }

    /**
     * CRITICAL: Cancel order with API key
     */
    @Transactional
    public void cancelOrderWithApiKey(Long orderId, String apiKey) {
        User user = userRepository.findByApiKeyHashAndIsActiveTrue(hashApiKey(apiKey))
                .orElseThrow(() -> new OrderValidationException("Invalid API key"));

        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new OrderValidationException("Order not found"));

        if (order.getStatus().equals(OrderStatus.COMPLETED) || 
            order.getStatus().equals(OrderStatus.CANCELLED)) {
            throw new OrderValidationException("Cannot cancel order in " + order.getStatus() + " status");
        }

        // Cancel order and refund balance
        BigDecimal refundAmount = calculateRefund(order);
        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            balanceService.refund(user, refundAmount, order, "Refund for cancelled order " + orderId);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepository.save(order);

        log.info("Order {} cancelled by user {}, refund: {}", orderId, user.getUsername(), refundAmount);
    }

    // Private helper methods

    private String hashApiKey(String apiKey) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private BigDecimal calculateCharge(com.smmpanel.entity.Service service, int quantity) {
        return service.getPricePer1000()
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal calculateRefund(Order order) {
        if (order.getRemains() <= 0) {
            return BigDecimal.ZERO;
        }
        
        // Refund proportional to remaining quantity
        BigDecimal totalCharge = order.getCharge();
        BigDecimal completedRatio = BigDecimal.valueOf(order.getQuantity() - order.getRemains())
                .divide(BigDecimal.valueOf(order.getQuantity()), 4, java.math.RoundingMode.HALF_UP);
        
        return totalCharge.subtract(totalCharge.multiply(completedRatio));
    }

    private OrderResponse mapToOrderResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .service(order.getService().getId().intValue())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(mapToPerfectPanelStatus(order.getStatus()))
                .charge(order.getCharge().toString())
                .build();
    }

    private Map<String, Object> mapToServiceMap(com.smmpanel.entity.Service service) {
        Map<String, Object> serviceMap = new HashMap<>();
        serviceMap.put("service", service.getId());
        serviceMap.put("name", service.getName());
        serviceMap.put("category", service.getCategory());
        serviceMap.put("rate", service.getPricePer1000().toString());
        serviceMap.put("min", service.getMinOrder());
        serviceMap.put("max", service.getMaxOrder());
        serviceMap.put("description", service.getDescription());
        return serviceMap;
    }

    /**
     * CRITICAL: Status mapping MUST match Perfect Panel exactly
     */
    private String mapToPerfectPanelStatus(OrderStatus status) {
        return switch (status) {
            case PENDING -> "Pending";
            case IN_PROGRESS, PROCESSING -> "In progress";
            case ACTIVE -> "In progress";
            case PARTIAL -> "Partial";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Canceled";  // Note: Perfect Panel uses "Canceled"
            case PAUSED -> "Paused";
            case HOLDING -> "In progress";
            case REFILL -> "Refill";
        };
    }
}