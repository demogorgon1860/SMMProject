package com.smmpanel.dto.balance;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Response DTO for balance information
 */
@Data
@Builder
public class BalanceResponse {
    private BigDecimal balance;
    private String currency;
    
    // Additional metadata can be added here as needed
    // e.g., pending deposits, available balance, etc.
}
