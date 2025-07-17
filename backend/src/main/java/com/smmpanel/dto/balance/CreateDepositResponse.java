package com.smmpanel.dto.balance;

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
public class CreateDepositResponse {
    private String orderId;
    private String paymentUrl;
    private BigDecimal amount;
    private String currency;
    private String cryptoAmount;
    private LocalDateTime expiresAt;
}