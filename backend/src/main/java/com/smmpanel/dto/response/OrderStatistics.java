package com.smmpanel.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatistics {
    private long totalOrders;
    private long pendingOrders;
    private long activeOrders;
    private long completedOrders;
    private long cancelledOrders;
    private double averageCompletionTime;
    private double successRate;
} 