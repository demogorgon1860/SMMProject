package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;

@Data
@Builder
public class OrderStatisticsResponse {
    private Long totalOrders;
    private Long pendingOrders;
    private Long activeOrders;
    private Long completedOrders;
    private BigDecimal totalRevenue;
    private BigDecimal todayRevenue;
} 