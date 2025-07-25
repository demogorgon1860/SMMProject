package com.smmpanel.service;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.ServiceNotFoundException;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final YouTubeService youTubeService;
    private final BalanceService balanceService;

    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        // Get current user
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Validate service
        Service service = serviceRepository.findById(Long.valueOf(request.getService()))
                .orElseThrow(() -> new ServiceNotFoundException("Service not found"));

        if (!service.getActive()) {
            throw new ServiceNotFoundException("Service is not active");
        }

        // Validate quantity
        if (request.getQuantity() < service.getMinOrder() || 
            request.getQuantity() > service.getMaxOrder()) {
            throw new IllegalArgumentException("Quantity must be between " + 
                service.getMinOrder() + " and " + service.getMaxOrder());
        }

        // Calculate charge
        BigDecimal charge = service.getPricePer1000()
                .multiply(BigDecimal.valueOf(request.getQuantity()))
                .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);

        // Check balance
        if (user.getBalance().compareTo(charge) < 0) {
            throw new InsufficientBalanceException("Insufficient balance");
        }

        // Extract YouTube video ID
        String videoId = youTubeService.extractVideoId(request.getLink());

        // Create order
        Order order = new Order();
        order.setUser(user);
        order.setService(service);
        order.setLink(request.getLink());
        order.setQuantity(request.getQuantity());
        order.setCharge(charge);
        order.setRemains(request.getQuantity());
        order.setYoutubeVideoId(videoId);
        order.setStatus(OrderStatus.PENDING);

        order = orderRepository.save(order);

        // Deduct balance
        balanceService.deductBalance(user, charge, order, "Order #" + order.getId());

        // Send to Kafka for processing
        kafkaTemplate.send("order-processing", order.getId());

        log.info("Order created: {} for user: {}", order.getId(), username);

        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getUserOrders(String status, Pageable pageable) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Page<Order> orders;
        if (status != null) {
            OrderStatus orderStatus = OrderStatus.valueOf(status.toUpperCase());
            orders = orderRepository.findByUserAndStatus(user, orderStatus, pageable);
        } else {
            orders = orderRepository.findByUser(user, pageable);
        }

        return orders.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Order order = orderRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        return mapToResponse(order);
    }

    private OrderResponse mapToResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .service(order.getService().getId().intValue())
                .link(order.getLink())
                .quantity(order.getQuantity())
                .startCount(order.getStartCount())
                .remains(order.getRemains())
                .status(mapStatus(order.getStatus()))
                .charge(order.getCharge().toString())
                .build();
    }

    private String mapStatus(OrderStatus status) {
        // Map to Perfect Panel compatible status strings
        return switch (status) {
            case PENDING -> "Pending";
            case IN_PROGRESS, PROCESSING -> "In progress";
            case ACTIVE -> "In progress";
            case PARTIAL -> "Partial";
            case COMPLETED -> "Completed";
            case CANCELLED -> "Canceled";
            case PAUSED -> "Paused";
            case HOLDING -> "In progress";
            case REFILL -> "Refill";
        };
    }
}