package com.smmpanel.dto.response;

import lombok.Data;

@Data
public class OrderStatistics {
    private long totalOrders;
    private long activeOrders;
    private long completedOrders;
    private double averageCompletionTime;
    private double successRate;
} 