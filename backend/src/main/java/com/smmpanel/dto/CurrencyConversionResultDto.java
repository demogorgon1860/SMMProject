package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;

@Data
@Builder
public class CurrencyConversionResultDto {
    private BigDecimal originalAmount;
    private String originalCurrency;
    private BigDecimal convertedAmount;
    private String targetCurrency;
    private BigDecimal exchangeRate;
    private String formattedAmount;
} 