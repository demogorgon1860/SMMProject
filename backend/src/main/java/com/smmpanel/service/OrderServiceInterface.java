package com.smmpanel.service;

import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.dto.order.*;
import com.smmpanel.dto.request.BulkOrderRequest;
import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderServiceInterface {
    // Add ALL missing method signatures
    OrderResponse createOrder(CreateOrderRequest request, String username);

    OrderResponse createOrder(OrderCreateRequest request, String username); // Alternative DTO

    OrderResponse getOrder(Long orderId, String username);

    Page<OrderResponse> getUserOrders(String username, String status, Pageable pageable);

    void cancelOrder(Long orderId, String username);

    OrderStatistics getOrderStatistics(int days);

    BulkOperationResult performBulkOperation(BulkOrderRequest request);

    HealthStatus getHealthStatus();
}
