package com.smmpanel.controller;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.request.BulkOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.dto.response.OrderStatistics;
import com.smmpanel.dto.response.BulkOperationResult;
import com.smmpanel.dto.response.HealthStatus;
import com.smmpanel.service.OrderService;
import com.smmpanel.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * ENHANCED Order Controller with proper validation and security
 * 
 * IMPROVEMENTS:
 * 1. Added comprehensive input validation with @Valid
 * 2. Implemented rate limiting per user
 * 3. Added proper API documentation with OpenAPI
 * 4. Security annotations for authorization
 * 5. Perfect Panel compatible response format
 * 6. Proper error handling and logging
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Validated
@Tag(name = "Orders", description = "Order management operations")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderService orderService;
    private final RateLimitService rateLimitService;

    /**
     * Create a new order with comprehensive validation
     */
    @PostMapping
    @Operation(
        summary = "Create new order",
        description = "Create a new order with automatic processing pipeline",
        responses = {
            @ApiResponse(responseCode = "201", description = "Order created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "402", description = "Insufficient balance"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
        }
    )
    public ResponseEntity<PerfectPanelResponse<?>> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            Principal principal) {
        
        // Apply rate limiting per user
        rateLimitService.checkRateLimit(principal.getName(), "create_order");
        
        log.info("Creating order for user: {} with service: {}", principal.getName(), request.getService());
        
        OrderResponse order = orderService.createOrder(request, principal.getName());
        
        // Perfect Panel compatible response format
        PerfectPanelResponse<OrderResponse> response = PerfectPanelResponse.<OrderResponse>builder()
                .data(order)
                .success(true)
                .message("Order created successfully")
                .build();
                
        return ResponseEntity.status(201).body(response);
    }

    /**
     * Get user's orders with pagination and filtering
     */
    @GetMapping
    @Operation(
        summary = "Get user orders",
        description = "Retrieve user's orders with optional status filtering and pagination"
    )
    public ResponseEntity<PerfectPanelResponse<Page<OrderResponse>>> getOrders(
            @Parameter(description = "Filter by order status")
            @RequestParam(required = false) 
            @Pattern(regexp = "PENDING|IN_PROGRESS|PROCESSING|ACTIVE|PARTIAL|COMPLETED|CANCELLED|PAUSED|HOLDING|REFILL", 
                    message = "Invalid order status")
            String status,
            
            @Parameter(description = "Page number (0-based)")
            @RequestParam(defaultValue = "0") 
            @Min(value = 0, message = "Page number must be non-negative") 
            int page,
            
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") 
            @Min(value = 1, message = "Page size must be positive")
            @jakarta.validation.constraints.Max(value = 100, message = "Page size cannot exceed 100")
            int size,
            
            @Parameter(description = "Sort field")
            @RequestParam(defaultValue = "createdAt") 
            @Pattern(regexp = "id|createdAt|updatedAt|status|quantity", 
                    message = "Invalid sort field")
            String sort,
            
            @Parameter(description = "Sort direction")
            @RequestParam(defaultValue = "desc") 
            @Pattern(regexp = "asc|desc", message = "Sort direction must be 'asc' or 'desc'")
            String direction,
            
            Principal principal) {

        Sort.Direction sortDirection = Sort.Direction.fromString(direction);
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));
        
        Page<OrderResponse> orders = orderService.getUserOrders(principal.getName(), status, pageable);
        
        PerfectPanelResponse<Page<OrderResponse>> response = PerfectPanelResponse.<Page<OrderResponse>>builder()
                .data(orders)
                .success(true)
                .message("Orders retrieved successfully")
                .build();
                
        return ResponseEntity.ok(response);
    }

    /**
     * Get specific order by ID
     */
    @GetMapping("/{id}")
    @Operation(
        summary = "Get order by ID",
        description = "Retrieve detailed information about a specific order"
    )
    public ResponseEntity<PerfectPanelResponse<OrderResponse>> getOrder(
            @Parameter(description = "Order ID", required = true)
            @PathVariable 
            @Min(value = 1, message = "Order ID must be positive") 
            Long id,
            Principal principal) {

        OrderResponse order = orderService.getOrder(id, principal.getName());
        
        PerfectPanelResponse<OrderResponse> response = PerfectPanelResponse.<OrderResponse>builder()
                .data(order)
                .success(true)
                .message("Order retrieved successfully")
                .build();
                
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel an order (user can only cancel their own orders in certain states)
     */
    @PostMapping("/{id}/cancel")
    @Operation(
        summary = "Cancel order",
        description = "Cancel an order if it's in a cancellable state"
    )
    public ResponseEntity<PerfectPanelResponse<Void>> cancelOrder(
            @Parameter(description = "Order ID", required = true)
            @PathVariable 
            @Min(value = 1, message = "Order ID must be positive") 
            Long id,
            Principal principal) {

        orderService.cancelOrder(id, principal.getName());
        
        PerfectPanelResponse<Void> response = PerfectPanelResponse.<Void>builder()
                .success(true)
                .message("Order cancelled successfully")
                .build();
                
        return ResponseEntity.ok(response);
    }

    /**
     * Get order statistics (requires operator role or higher)
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('OPERATOR') or hasRole('ADMIN')")
    @Operation(
        summary = "Get order statistics",
        description = "Retrieve order statistics (requires operator privileges)"
    )
    public ResponseEntity<PerfectPanelResponse<OrderStatistics>> getOrderStatistics(
            @Parameter(description = "Days to look back")
            @RequestParam(defaultValue = "30") 
            @Min(value = 1, message = "Days must be positive")
            @jakarta.validation.constraints.Max(value = 365, message = "Cannot look back more than 365 days")
            int days) {

        OrderStatistics stats = orderService.getOrderStatistics(days);
        
        PerfectPanelResponse<OrderStatistics> response = PerfectPanelResponse.<OrderStatistics>builder()
                .data(stats)
                .success(true)
                .message("Statistics retrieved successfully")
                .build();
                
        return ResponseEntity.ok(response);
    }

    /**
     * Bulk order operations (admin only)
     */
    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Bulk order operations",
        description = "Perform bulk operations on multiple orders (admin only)"
    )
    public ResponseEntity<PerfectPanelResponse<BulkOperationResult>> bulkOperation(
            @Valid @RequestBody BulkOrderRequest request,
            Principal principal) {

        BulkOperationResult result = orderService.performBulkOperation(request);
        
        PerfectPanelResponse<BulkOperationResult> response = PerfectPanelResponse.<BulkOperationResult>builder()
                .data(result)
                .success(true)
                .message("Bulk operation completed")
                .build();
                
        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for order service
     */
    @GetMapping("/health")
    @Operation(
        summary = "Order service health check",
        description = "Check the health status of order processing pipeline"
    )
    public ResponseEntity<PerfectPanelResponse<HealthStatus>> healthCheck() {
        HealthStatus health = orderService.getHealthStatus();
        
        PerfectPanelResponse<HealthStatus> response = PerfectPanelResponse.<HealthStatus>builder()
                .data(health)
                .success(health.isHealthy())
                .message(health.isHealthy() ? "Service healthy" : "Service degraded")
                .build();
                
        return ResponseEntity.ok(response);
    }
}