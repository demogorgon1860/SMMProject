package com.smmpanel.dto.balance;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for deposit response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositResponse {
    private Long id;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String paymentMethod;
    private String transactionId;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
} 