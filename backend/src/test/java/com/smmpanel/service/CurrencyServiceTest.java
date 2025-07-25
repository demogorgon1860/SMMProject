package com.smmpanel.service;

import com.smmpanel.config.CurrencyConfig;
import com.smmpanel.dto.balance.CurrencyConversionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrencyServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private CurrencyService currencyService;

    private final String defaultCurrency = "USD";
    private final String exchangeRateApiUrl = "https://api.exchangerate.host/latest";
    private final String baseCurrency = "USD";

    @BeforeEach
    void setUp() {
        currencyService = new CurrencyService(defaultCurrency, exchangeRateApiUrl, baseCurrency);
        ReflectionTestUtils.setField(currencyService, "restTemplate", restTemplate);
        
        // Mock the REST template response
        CurrencyConversionResponse mockResponse = new CurrencyConversionResponse();
        mockResponse.setSuccess(true);
        mockResponse.setRates(Map.of(
                "EUR", new BigDecimal("0.85"),
                "GBP", new BigDecimal("0.73"),
                "JPY", new BigDecimal("109.50")
        ));
        
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(mockResponse);
    }

    @Test
    void convertCurrency_SameCurrency_ReturnsSameAmount() {
        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal result = currencyService.convertCurrency(amount, "USD", "USD");
        
        assertEquals(0, amount.compareTo(result));
        assertEquals(2, result.scale());
    }

    @Test
    void convertCurrency_DifferentCurrencies_ReturnsConvertedAmount() {
        BigDecimal amount = new BigDecimal("100.00");
        
        // Convert USD to EUR
        BigDecimal result = currencyService.convertCurrency(amount, "USD", "EUR");
        assertNotNull(result);
        assertEquals(2, result.scale());
        
        // Convert EUR to USD
        BigDecimal backToUsd = currencyService.convertCurrency(result, "EUR", "USD");
        assertTrue(backToUsd.compareTo(new BigDecimal("99.99")) > 0);
        assertTrue(backToUsd.compareTo(new BigDecimal("100.01")) < 0);
    }

    @Test
    void formatCurrency_ValidInput_ReturnsFormattedString() {
        BigDecimal amount = new BigDecimal("1234.5678");
        
        // Test USD formatting
        String usdFormatted = currencyService.formatCurrency(amount, "USD");
        assertEquals("$1,234.57", usdFormatted);
        
        // Test JPY formatting (0 decimal places)
        String jpyFormatted = currencyService.formatCurrency(amount, "JPY");
        assertTrue(jpyFormatted.startsWith("¥1,235"));
    }

    @Test
    void getExchangeRate_ValidCurrency_ReturnsRate() {
        BigDecimal rate = currencyService.getExchangeRate("EUR");
        assertNotNull(rate);
        assertEquals(0, new BigDecimal("0.85").compareTo(rate));
    }

    @Test
    void getExchangeRate_BaseCurrency_ReturnsOne() {
        BigDecimal rate = currencyService.getExchangeRate("USD");
        assertEquals(0, BigDecimal.ONE.compareTo(rate));
    }

    @Test
    void getDecimalPlaces_ValidCurrency_ReturnsCorrectPlaces() {
        assertEquals(2, currencyService.getDecimalPlaces("USD"));
        assertEquals(0, currencyService.getDecimalPlaces("JPY"));
        assertEquals(8, currencyService.getDecimalPlaces("BTC"));
    }

    @Test
    void getCurrencySymbol_ValidCurrency_ReturnsSymbol() {
        assertEquals("$", currencyService.getCurrencySymbol("USD"));
        assertEquals("€", currencyService.getCurrencySymbol("EUR"));
        assertEquals("£", currencyService.getCurrencySymbol("GBP"));
    }

    @Test
    void getSupportedCurrencies_ReturnsAllCurrencies() {
        var currencies = currencyService.getSupportedCurrencies();
        assertFalse(currencies.isEmpty());
        assertTrue(currencies.containsKey("USD"));
        assertTrue(currencies.containsKey("EUR"));
        assertTrue(currencies.containsKey("BTC"));
    }

    @Test
    void fetchLatestRates_UpdatesExchangeRates() {
        // Clear any existing rates
        ReflectionTestUtils.setField(currencyService, "exchangeRates", null);
        
        // Trigger the fetch
        currencyService.fetchLatestRates();
        
        // Verify rates were updated
        assertNotNull(ReflectionTestUtils.getField(currencyService, "exchangeRates"));
        assertFalse(((Map<?, ?>)ReflectionTestUtils.getField(currencyService, "exchangeRates")).isEmpty());
    }
}
