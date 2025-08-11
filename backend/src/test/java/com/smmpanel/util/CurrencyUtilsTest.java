package com.smmpanel.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.smmpanel.exception.ExchangeRateException;
import com.smmpanel.service.CurrencyService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurrencyUtilsTest {

    @Mock private CurrencyService currencyService;

    @BeforeEach
    void setUp() {
        // Setup mock responses for currency service
        when(currencyService.getSupportedCurrencies())
                .thenReturn(
                        Map.of(
                                "USD", new CurrencyService.CurrencyInfo("$", 2, "US Dollar", false),
                                "EUR", new CurrencyService.CurrencyInfo("€", 2, "Euro", false),
                                "JPY",
                                        new CurrencyService.CurrencyInfo(
                                                "¥", 0, "Japanese Yen", false),
                                "BHD",
                                        new CurrencyService.CurrencyInfo(
                                                ".د.ب", 3, "Bahraini Dinar", false)));

        when(currencyService.getDecimalPlaces("USD")).thenReturn(2);
        when(currencyService.getDecimalPlaces("EUR")).thenReturn(2);
        when(currencyService.getDecimalPlaces("JPY")).thenReturn(0);
        when(currencyService.getDecimalPlaces("BHD")).thenReturn(3);

        when(currencyService.getCurrencySymbol("USD")).thenReturn("$");
        when(currencyService.getCurrencySymbol("EUR")).thenReturn("€");
        when(currencyService.getCurrencySymbol("JPY")).thenReturn("¥");
        when(currencyService.getCurrencySymbol("BHD")).thenReturn(".د.ب");

        when(currencyService.isSymbolAfterAmount("USD")).thenReturn(false);
        when(currencyService.isSymbolAfterAmount("EUR")).thenReturn(true);
    }

    @Test
    void validateCurrencyCode_ValidCode_NoException() {
        assertDoesNotThrow(() -> CurrencyUtils.validateCurrencyCode("USD", currencyService));
    }

    @Test
    void validateCurrencyCode_NullCode_ThrowsException() {
        assertThrows(
                ExchangeRateException.class,
                () -> CurrencyUtils.validateCurrencyCode(null, currencyService));
    }

    @Test
    void validateCurrencyCode_EmptyCode_ThrowsException() {
        assertThrows(
                ExchangeRateException.class,
                () -> CurrencyUtils.validateCurrencyCode("", currencyService));
    }

    @Test
    void validateCurrencyCode_UnsupportedCode_ThrowsException() {
        assertThrows(
                ExchangeRateException.class,
                () -> CurrencyUtils.validateCurrencyCode("XYZ", currencyService));
    }

    @Test
    void formatCurrency_USD_FormatsCorrectly() {
        String result =
                CurrencyUtils.formatCurrency(new BigDecimal("1234.567"), "USD", currencyService);
        assertEquals("$1,234.57", result);
    }

    @Test
    void formatCurrency_EUR_FormatsWithSymbolAfter() {
        String result =
                CurrencyUtils.formatCurrency(new BigDecimal("1234.5"), "EUR", currencyService);
        assertEquals("1,234.50 €", result);
    }

    @Test
    void formatCurrency_JPY_RoundsToNoDecimals() {
        String result =
                CurrencyUtils.formatCurrency(new BigDecimal("1234.56"), "JPY", currencyService);
        assertEquals("¥1,235", result);
    }

    @Test
    void formatCurrency_BHD_ThreeDecimalPlaces() {
        String result =
                CurrencyUtils.formatCurrency(new BigDecimal("1234.5678"), "BHD", currencyService);
        assertEquals(".د.ب1,234.568", result);
    }

    @Test
    void convertAmount_ConvertsCorrectly() {
        BigDecimal amount = new BigDecimal("100");
        BigDecimal exchangeRate = new BigDecimal("0.85");

        BigDecimal result =
                CurrencyUtils.convertAmount(amount, exchangeRate, "EUR", currencyService);

        assertEquals(0, new BigDecimal("85.00").compareTo(result));
    }

    @Test
    void parseMonetaryValue_WithCommaDecimal() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("1.234,56 €");
        assertEquals(0, new BigDecimal("1234.56").compareTo(result));
    }

    @Test
    void parseMonetaryValue_WithDotDecimal() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("$1,234.56");
        assertEquals(0, new BigDecimal("1234.56").compareTo(result));
    }

    @Test
    void parseMonetaryValue_WithThousandsSeparators() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("1,234,567.89");
        assertEquals(0, new BigDecimal("1234567.89").compareTo(result));
    }

    @Test
    void parseMonetaryValue_WithEuropeanFormat() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("1.234.567,89");
        assertEquals(0, new BigDecimal("1234567.89").compareTo(result));
    }

    @Test
    void parseMonetaryValue_WithNegativeValue() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("-$1,234.56");
        assertEquals(0, new BigDecimal("-1234.56").compareTo(result));
    }

    @Test
    void parseMonetaryValue_WithSpaceSeparators() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("1 234 567,89");
        assertEquals(0, new BigDecimal("1234567.89").compareTo(result));
    }

    @Test
    void parseMonetaryValue_PureInteger() {
        BigDecimal result = CurrencyUtils.parseMonetaryValue("1234567");
        assertEquals(0, new BigDecimal("1234567").compareTo(result));
    }

    @Test
    void parseMonetaryValue_NullInput_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> CurrencyUtils.parseMonetaryValue(null));
    }

    @Test
    void parseMonetaryValue_EmptyInput_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> CurrencyUtils.parseMonetaryValue(""));
    }

    @Test
    void parseMonetaryValue_OnlyCurrencySymbol_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> CurrencyUtils.parseMonetaryValue("€"));
    }

    @Test
    void roundMonetaryValue_RoundsCorrectly() {
        BigDecimal amount = new BigDecimal("123.4567");
        BigDecimal result = CurrencyUtils.roundMonetaryValue(amount, "USD", currencyService);
        assertEquals(0, new BigDecimal("123.46").compareTo(result));
    }

    @Test
    void roundMonetaryValue_JPY_RoundsToNoDecimals() {
        BigDecimal amount = new BigDecimal("123.56");
        BigDecimal result = CurrencyUtils.roundMonetaryValue(amount, "JPY", currencyService);
        assertEquals(0, new BigDecimal("124").compareTo(result));
    }

    @Test
    void validatePositiveAmount_ValidAmount_NoException() {
        assertDoesNotThrow(
                () -> CurrencyUtils.validatePositiveAmount(new BigDecimal("100"), "amount"));
    }

    @Test
    void validatePositiveAmount_Null_ThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CurrencyUtils.validatePositiveAmount(null, "amount"));
    }

    @Test
    void validatePositiveAmount_Zero_ThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CurrencyUtils.validatePositiveAmount(BigDecimal.ZERO, "amount"));
    }

    @Test
    void validatePositiveAmount_Negative_ThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CurrencyUtils.validatePositiveAmount(new BigDecimal("-100"), "amount"));
    }
}
