package com.smmpanel.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {
    private BigDecimal balance;
    private String currency;
    private LocalDateTime lastUpdated;
    private BigDecimal totalSpent;
    private Long totalOrders;
}
