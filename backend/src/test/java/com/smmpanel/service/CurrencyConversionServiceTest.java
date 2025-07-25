package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ExchangeRateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrencyConversionServiceTest {

    @Mock
    private ExchangeRateService exchangeRateService;

    @Mock
    private CurrencyService currencyService;

    @InjectMocks
    private CurrencyConversionService currencyConversionService;

    private final User testUser = User.builder()
            .id(1L)
            .username("testuser")
            .preferredCurrency("EUR")
            .build();

    @BeforeEach
    void setUp() {
        // Setup mock responses
        when(currencyService.getSupportedCurrencies())
                .thenReturn(Map.of(
                        "USD", new CurrencyService.CurrencyInfo("$", 2, "US Dollar"),
                        "EUR", new CurrencyService.CurrencyInfo("€", 2, "Euro"),
                        "JPY", new CurrencyService.CurrencyInfo("¥", 0, "Japanese Yen")
                ));
        
        when(currencyService.getDecimalPlaces("USD")).thenReturn(2);
        when(currencyService.getDecimalPlaces("EUR")).thenReturn(2);
        when(currencyService.getDecimalPlaces("JPY")).thenReturn(0);
        
        when(currencyService.getCurrencySymbol("USD")).thenReturn("$");
        when(currencyService.getCurrencySymbol("EUR")).thenReturn("€");
        when(currencyService.getCurrencySymbol("JPY")).thenReturn("¥");
        
        when(exchangeRateService.getExchangeRate("USD", "EUR"))
                .thenReturn(new BigDecimal("0.85"));
        when(exchangeRateService.getExchangeRate("EUR", "USD"))
                .thenReturn(new BigDecimal("1.18"));
        when(exchangeRateService.getExchangeRate("USD", "JPY"))
                .thenReturn(new BigDecimal("110.50"));
    }

    @Test
    void convert_SameCurrency_ReturnsSameAmount() {
        CurrencyConversionRequest request = new CurrencyConversionRequest(
                new BigDecimal("100"), "USD", "USD", false);
        
        CurrencyConversionResponse response = currencyConversionService.convert(request);
        
        assertEquals(0, new BigDecimal("100").compareTo(response.getConvertedAmount()));
        assertEquals(0, BigDecimal.ONE.compareTo(response.getExchangeRate()));
        assertNull(response.getFormattedAmount());
        verifyNoInteractions(exchangeRateService);
    }

    @Test
    void convert_DifferentCurrencies_ReturnsConvertedAmount() {
        CurrencyConversionRequest request = new CurrencyConversionRequest(
                new BigDecimal("100"), "USD", "EUR", false);
        
        CurrencyConversionResponse response = currencyConversionService.convert(request);
        
        assertEquals(0, new BigDecimal("85.00").compareTo(response.getConvertedAmount()));
        assertEquals(0, new BigDecimal("0.85").compareTo(response.getExchangeRate()));
        assertNull(response.getFormattedAmount());
    }

    @Test
    void convert_WithFormatting_ReturnsFormattedAmount() {
        CurrencyConversionRequest request = new CurrencyConversionRequest(
                new BigDecimal("100"), "USD", "EUR", true);
        
        when(currencyService.formatCurrency(any(BigDecimal.class), eq("EUR")))
                .thenReturn("85,00 €");
        
        CurrencyConversionResponse response = currencyConversionService.convert(request);
        
        assertNotNull(response.getFormattedAmount());
        assertEquals("85,00 €", response.getFormattedAmount());
    }

    @Test
    void convert_UnsupportedCurrency_ThrowsException() {
        CurrencyConversionRequest request = new CurrencyConversionRequest(
                new BigDecimal("100"), "USD", "XYZ", false);
        
        assertThrows(ExchangeRateException.class, 
                () -> currencyConversionService.convert(request));
    }

    @Test
    void formatCurrency_ValidInput_ReturnsFormattedString() {
        when(currencyService.formatCurrency(new BigDecimal("1234.56"), "USD"))
                .thenReturn("$1,234.56");
        
        String result = currencyConversionService.formatCurrency(
                new BigDecimal("1234.56"), "USD");
        
        assertEquals("$1,234.56", result);
    }

    @Test
    void formatForUser_ValidInput_ReturnsFormattedString() {
        when(currencyService.formatCurrency(new BigDecimal("100"), "EUR"))
                .thenReturn("100,00 €");
        
        String result = currencyConversionService.formatForUser(
                new BigDecimal("100"), testUser);
        
        assertEquals("100,00 €", result);
    }

    @Test
    void convertAndFormatForUser_SameCurrency_ReturnsFormattedAmount() {
        when(currencyService.formatCurrency(new BigDecimal("100"), "EUR"))
                .thenReturn("100,00 €");
        
        String result = currencyConversionService.convertAndFormatForUser(
                new BigDecimal("100"), "EUR", testUser);
        
        assertEquals("100,00 €", result);
        verify(exchangeRateService, never()).getExchangeRate(anyString(), anyString());
    }

    @Test
    void convertAndFormatForUser_DifferentCurrencies_ReturnsConvertedAndFormatted() {
        when(currencyService.formatCurrency(new BigDecimal("85.00"), "EUR"))
                .thenReturn("85,00 €");
        
        String result = currencyConversionService.convertAndFormatForUser(
                new BigDecimal("100"), "USD", testUser);
        
        assertEquals("85,00 €", result);
        verify(exchangeRateService).getExchangeRate("USD", "EUR");
    }

    @Test
    void getExchangeRate_CachesResults() {
        // First call - should call the exchange rate service
        BigDecimal rate1 = currencyConversionService.getExchangeRate("USD", "EUR");
        assertEquals(0, new BigDecimal("0.85").compareTo(rate1));

        // Second call with same parameters - should be cached
        BigDecimal rate2 = currencyConversionService.getExchangeRate("USD", "EUR");
        assertEquals(0, new BigDecimal("0.85").compareTo(rate2));

        // Verify exchange rate service was only called once
        verify(exchangeRateService, times(1)).getExchangeRate("USD", "EUR");
    }
}
