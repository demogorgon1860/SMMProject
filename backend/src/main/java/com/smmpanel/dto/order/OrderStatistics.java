package com.smmpanel.dto.order;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatistics {
    private Long totalOrders;
    private Long activeOrders;
    private Long completedOrders;
    private BigDecimal totalRevenue;
    private BigDecimal averageOrderValue;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
} 