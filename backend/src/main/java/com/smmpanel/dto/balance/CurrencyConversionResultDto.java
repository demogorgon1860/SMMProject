package com.smmpanel.dto.balance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTO representing the result of a currency conversion operation */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CurrencyConversionResultDto {

    /** The original amount to be converted */
    private BigDecimal amount;

    /** The source currency code (e.g., "USD") */
    private String fromCurrency;

    /** The target currency code (e.g., "EUR") */
    private String toCurrency;

    /**
     * The exchange rate used for conversion (1 unit of fromCurrency = exchangeRate units of
     * toCurrency)
     */
    private BigDecimal exchangeRate;

    /** The converted amount in the target currency */
    private BigDecimal convertedAmount;

    /** The formatted string representation of the converted amount (e.g., "€85.00") */
    private String formattedAmount;

    /** Creates a new builder with the required fields */
    public static CurrencyConversionResultDtoBuilder from(CurrencyConversionRequest request) {
        return builder()
                .amount(request.getAmount())
                .fromCurrency(request.getFromCurrency().toUpperCase())
                .toCurrency(request.getToCurrency().toUpperCase())
                .format(request.isFormat());
    }

    /** Creates a new builder with the required fields from individual parameters */
    public static CurrencyConversionResultDtoBuilder from(
            BigDecimal amount, String fromCurrency, String toCurrency, boolean format) {

        return builder()
                .amount(amount)
                .fromCurrency(fromCurrency.toUpperCase())
                .toCurrency(toCurrency.toUpperCase())
                .format(format);
    }

    /** Builder class with additional convenience methods */
    public static class CurrencyConversionResultDtoBuilder {

        private boolean format = true;

        /** Sets the format flag */
        public CurrencyConversionResultDtoBuilder format(boolean format) {
            this.format = format;
            return this;
        }

        /** Builds the DTO with the exchange rate and converted amount */
        public CurrencyConversionResultDto buildWithRates(
                BigDecimal exchangeRate, BigDecimal convertedAmount) {

            this.exchangeRate = exchangeRate;
            this.convertedAmount = convertedAmount;

            return this.build();
        }
    }
}
