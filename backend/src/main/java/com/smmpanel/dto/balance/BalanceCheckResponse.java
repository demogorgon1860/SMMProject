package com.smmpanel.dto.balance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO for balance check
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceCheckResponse {
    private boolean hasSufficientBalance;
    private BigDecimal requestedAmount;
    private String currency;
    
    // Additional metadata can be added here as needed
    @Builder.Default
    private boolean isEstimated = false;
}
