package com.smmpanel.dto.response;

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
public class TransactionHistoryResponse {
    private Long id;
    private String type; // DEPOSIT, WITHDRAWAL, ORDER_PAYMENT, REFUND
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String description;
    private String status; // COMPLETED, PENDING, FAILED
    private LocalDateTime createdAt;
    private Long orderId; // If related to an order
    private String referenceNumber;
}
