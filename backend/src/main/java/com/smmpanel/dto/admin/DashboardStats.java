package com.smmpanel.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStats {
    private Long totalOrders;
    private Long ordersLast24h;
    private Long ordersLast7Days;
    private Long ordersLast30Days;
    private Double totalRevenue;
    private Double revenueLast24h;
    private Double revenueLast7Days;
    private Double revenueLast30Days;
    private Integer activeOrders;
    private Integer pendingOrders;
    private Integer completedOrders;
    private Long totalUsers;
    private Integer activeYouTubeAccounts;
}
