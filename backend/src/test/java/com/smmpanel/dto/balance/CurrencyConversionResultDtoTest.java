package com.smmpanel.dto.balance;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CurrencyConversionResultDtoTest {

    @Test
    void fromRequest_WithRequest_SetsCorrectFields() {
        CurrencyConversionRequest request = new CurrencyConversionRequest();
        request.setAmount(new BigDecimal("100"));
        request.setFromCurrency("USD");
        request.setToCurrency("EUR");
        request.setFormat(true);

        CurrencyConversionResultDto dto =
                CurrencyConversionResultDto.from(request)
                        .buildWithRates(new BigDecimal("0.85"), new BigDecimal("85.00"));

        assertNotNull(dto);
        assertEquals(0, new BigDecimal("100").compareTo(dto.getAmount()));
        assertEquals("USD", dto.getFromCurrency());
        assertEquals("EUR", dto.getToCurrency());
        assertEquals(0, new BigDecimal("0.85").compareTo(dto.getExchangeRate()));
        assertEquals(0, new BigDecimal("85.00").compareTo(dto.getConvertedAmount()));
    }

    @Test
    void fromParams_WithParams_SetsCorrectFields() {
        CurrencyConversionResultDto dto =
                CurrencyConversionResultDto.from(new BigDecimal("100"), "USD", "EUR", true)
                        .buildWithRates(new BigDecimal("0.85"), new BigDecimal("85.00"));

        assertNotNull(dto);
        assertEquals(0, new BigDecimal("100").compareTo(dto.getAmount()));
        assertEquals("USD", dto.getFromCurrency());
        assertEquals("EUR", dto.getToCurrency());
        assertEquals(0, new BigDecimal("0.85").compareTo(dto.getExchangeRate()));
        assertEquals(0, new BigDecimal("85.00").compareTo(dto.getConvertedAmount()));
    }

    @Test
    void builder_WithFormatFalse_ExcludesFormattedAmount() {
        CurrencyConversionResultDto dto =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("100"))
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .exchangeRate(new BigDecimal("0.85"))
                        .convertedAmount(new BigDecimal("85.00"))
                        .format(false)
                        .build();

        assertNull(dto.getFormattedAmount());
    }

    @Test
    void builder_WithFormatTrue_IncludesFormattedAmount() {
        CurrencyConversionResultDto dto =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("100"))
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .exchangeRate(new BigDecimal("0.85"))
                        .convertedAmount(new BigDecimal("85.00"))
                        .formattedAmount("85,00 €")
                        .format(true)
                        .build();

        assertEquals("85,00 €", dto.getFormattedAmount());
    }

    @Test
    void builder_WithNullValues_DoesNotThrow() {
        assertDoesNotThrow(
                () -> {
                    CurrencyConversionResultDto.builder()
                            .amount(null)
                            .fromCurrency(null)
                            .toCurrency(null)
                            .exchangeRate(null)
                            .convertedAmount(null)
                            .formattedAmount(null)
                            .build();
                });
    }

    @Test
    void buildWithRates_WithRates_SetsCorrectFields() {
        CurrencyConversionResultDto dto =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("100"))
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .format(true)
                        .buildWithRates(new BigDecimal("0.85"), new BigDecimal("85.00"));

        assertNotNull(dto);
        assertEquals(0, new BigDecimal("0.85").compareTo(dto.getExchangeRate()));
        assertEquals(0, new BigDecimal("85.00").compareTo(dto.getConvertedAmount()));
    }

    @Test
    void equalsAndHashCode_WithSameValues_AreEqual() {
        CurrencyConversionResultDto dto1 =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("100"))
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .exchangeRate(new BigDecimal("0.85"))
                        .convertedAmount(new BigDecimal("85.00"))
                        .formattedAmount("85,00 €")
                        .build();

        CurrencyConversionResultDto dto2 =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("100"))
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .exchangeRate(new BigDecimal("0.85"))
                        .convertedAmount(new BigDecimal("85.00"))
                        .formattedAmount("85,00 €")
                        .build();

        assertEquals(dto1, dto2);
        assertEquals(dto1.hashCode(), dto2.hashCode());
    }

    @Test
    void equalsAndHashCode_WithDifferentValues_AreNotEqual() {
        CurrencyConversionResultDto dto1 =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("100"))
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .exchangeRate(new BigDecimal("0.85"))
                        .convertedAmount(new BigDecimal("85.00"))
                        .formattedAmount("85,00 €")
                        .build();

        CurrencyConversionResultDto dto2 =
                CurrencyConversionResultDto.builder()
                        .amount(new BigDecimal("200")) // Different amount
                        .fromCurrency("USD")
                        .toCurrency("EUR")
                        .exchangeRate(new BigDecimal("0.85"))
                        .convertedAmount(new BigDecimal("170.00")) // Different converted amount
                        .formattedAmount("170,00 €") // Different formatted amount
                        .build();

        assertNotEquals(dto1, dto2);
        assertNotEquals(dto1.hashCode(), dto2.hashCode());
    }
}
