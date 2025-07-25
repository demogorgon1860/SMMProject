package com.smmpanel.dto;

import com.smmpanel.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositResponse {
    private Long id;
    private String depositId;
    private BigDecimal amount;
    private String currency;
    private PaymentStatus status;
    private String paymentUrl;
    private String paymentMethod;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
} 