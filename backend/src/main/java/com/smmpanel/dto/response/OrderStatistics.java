package com.smmpanel.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatistics {
    private Long totalOrders;
    private Long pendingOrders;
    private Long processingOrders;
    private Long completedOrders;
    private Long cancelledOrders;
    private Long failedOrders;
    private BigDecimal totalRevenue;
    private BigDecimal averageOrderValue;
    private Double completionRate;
    private Double successRate;
}
