package com.smmpanel.dto.admin;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for displaying refills in the admin refills page. Combines refill information with order
 * details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminRefillDto {
    // Refill tracking information
    private Long refillId;
    private Long originalOrderId;
    private Long refillOrderId;
    private Integer refillNumber;

    // Quantities
    private Integer originalQuantity;
    private Integer deliveredQuantity;
    private Integer refillQuantity;
    private Long startCountAtRefill;

    // Refill order details (from the refill order)
    private String username;
    private String link;
    private String status;
    private Integer startCount; // Start count of the refill order
    private Integer remains;
    private LocalDateTime refillCreatedAt;

    // Calculated fields for display
    private String orderName; // Format: "Order #{originalOrderId} - Refill #{refillNumber}"
    private String binomOfferId;
    private String youtubeVideoId;
}
