package com.smmpanel.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** Response DTO for currency conversion rates from external API */
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class CurrencyConversionResponse {
    // Default constructor required for Jackson deserialization
    public CurrencyConversionResponse() {}

    // All-args constructor for Lombok builder
    public CurrencyConversionResponse(
            boolean success,
            long timestamp,
            String base,
            LocalDate date,
            Map<String, BigDecimal> rates,
            BigDecimal amount,
            ErrorInfo error) {
        this.success = success;
        this.timestamp = timestamp;
        this.base = base;
        this.date = date;
        this.rates = rates;
        this.amount = amount;
        this.error = error;
    }

    private boolean success;
    private long timestamp;
    private String base;
    private LocalDate date;
    private Map<String, BigDecimal> rates;
    private BigDecimal amount;

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
