package com.smmpanel.dto;

import com.smmpanel.entity.PaymentStatus;
import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
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