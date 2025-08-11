package com.smmpanel.dto;

import com.smmpanel.entity.OrderStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PROJECTION DTO: Order Summary for high-performance queries
 *
 * <p>Features: 1. Minimal data transfer - only essential fields 2. Prevents N+1 queries by
 * including joined data 3. Optimized for dashboard and listing views 4. Reduces memory usage
 * compared to full entities
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderSummaryDTO {

    private Long id;
    private String orderId;
    private OrderStatus status;
    private Integer quantity;
    private BigDecimal charge;
    private LocalDateTime createdAt;

    // User information (joined to prevent N+1)
    private String username;

    // Service information (joined to prevent N+1)
    private String serviceName;

    /** Check if order is in processing state */
    public boolean isProcessing() {
        return status == OrderStatus.PROCESSING || status == OrderStatus.ACTIVE;
    }

    /** Check if order is completed */
    public boolean isCompleted() {
        return status == OrderStatus.COMPLETED;
    }

    /** Check if order has errors */
    public boolean hasErrors() {
        return status == OrderStatus.HOLDING || status == OrderStatus.CANCELLED;
    }

    /** Get display-friendly status */
    public String getDisplayStatus() {
        return status != null ? status.name().toLowerCase().replace("_", " ") : "unknown";
    }

    /** Calculate age in hours */
    public long getAgeInHours() {
        return createdAt != null
                ? java.time.Duration.between(createdAt, LocalDateTime.now()).toHours()
                : 0;
    }
}
