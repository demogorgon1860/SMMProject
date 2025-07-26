package com.smmpanel.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Response DTO for currency conversion rates from external API
 */
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrencyConversionResponse {
    private boolean success;
    private long timestamp;
    private String base;
    private LocalDate date;
    private Map<String, BigDecimal> rates;
    
    @JsonProperty("error")
    private ErrorInfo error;
    
    public java.math.BigDecimal getConvertedAmount(String toCurrency) {
        if (rates != null && rates.containsKey(toCurrency)) {
            return rates.get(toCurrency);
        }
        return null;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorInfo {
        private String code;
        private String message;
    }
}
