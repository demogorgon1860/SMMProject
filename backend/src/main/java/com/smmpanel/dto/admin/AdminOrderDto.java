package com.smmpanel.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOrderDto {
    private Long id;
    private String username;
    private Long serviceId;
    private String serviceName;
    private String link;
    private Integer quantity;
    private BigDecimal charge;
    private Integer startCount;
    private Integer remains;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Additional fields for admin view
    private String orderName; // Format: "{startCount} views"

    /**
     * True when this order was created as a refill for an earlier completed/partial order. Lets the
     * admin orders table render a "Refill" badge so operators can distinguish a refill-issued row
     * from a fresh paid order at a glance.
     */
    private Boolean isRefill;

    /**
     * The {@code id} of the original order this refill was issued for, or {@code null} when this is
     * not a refill. Useful for admin debugging — a quick way to jump from the refill row back to
     * the order it's compensating without joining {@code order_refills} manually.
     */
    private Long refillParentId;
}
