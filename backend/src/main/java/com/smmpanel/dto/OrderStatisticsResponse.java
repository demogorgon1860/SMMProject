package com.smmpanel.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

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
