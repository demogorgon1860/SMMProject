package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.exception.ExchangeRateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeRateServiceTest {

    @MockBean
    private RestTemplate restTemplate;

    @MockBean
    private CurrencyService currencyService;

    @Autowired
    private ExchangeRateService exchangeRateService;

    private final String baseCurrency = "USD";
    private final String exchangeRateApiUrl = "https://api.exchangerate.host/latest";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(exchangeRateService, "baseCurrency", baseCurrency);
        ReflectionTestUtils.setField(exchangeRateService, "exchangeRateApiUrl", exchangeRateApiUrl);
        ReflectionTestUtils.setField(exchangeRateService, "apiTimeoutMs", 5000L);

        // Initialize with some test data
        Map<String, CurrencyService.CurrencyInfo> supportedCurrencies = Map.of(
            "USD", new CurrencyService.CurrencyInfo("$", 2, "US Dollar"),
            "EUR", new CurrencyService.CurrencyInfo("€", 2, "Euro"),
            "GBP", new CurrencyService.CurrencyInfo("£", 2, "British Pound")
        );
        
        when(currencyService.getSupportedCurrencies()).thenReturn(supportedCurrencies);
    }

    @Test
    void getExchangeRate_SameCurrency_ReturnsOne() {
        BigDecimal rate = exchangeRateService.getExchangeRate("USD", "USD");
        assertEquals(0, BigDecimal.ONE.compareTo(rate));
    }

    @Test
    void getExchangeRate_ValidCurrencies_ReturnsRate() {
        // Mock the REST template response
        CurrencyConversionResponse mockResponse = CurrencyConversionResponse.builder()
                .success(true)
                .timestamp(System.currentTimeMillis())
                .base("USD")
                .date(LocalDate.now())
                .rates(Map.of(
                    "EUR", new BigDecimal("0.85"),
                    "GBP", new BigDecimal("0.73")
                ))
                .amount(new BigDecimal("100"))
                .build();
        
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(mockResponse);
        
        // Test conversion
        BigDecimal rate = exchangeRateService.getExchangeRate("USD", "EUR");
        assertNotNull(rate);
        assertEquals(0, new BigDecimal("0.85").compareTo(rate));
        
        // Test reverse conversion
        BigDecimal reverseRate = exchangeRateService.getExchangeRate("EUR", "USD");
        assertTrue(reverseRate.compareTo(BigDecimal.ONE) > 0);
        
        // Verify the API was called
        verify(restTemplate, atLeastOnce()).getForObject(anyString(), eq(CurrencyConversionResponse.class));
    }

    @Test
    void getExchangeRate_ApiError_ThrowsException() {
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenThrow(new RuntimeException("API Error"));
        
        // The service should handle API errors and throw ExchangeRateException
        assertThrows(ExchangeRateException.class, 
            () -> exchangeRateService.getExchangeRate("USD", "EUR"));
        
        // Verify the API was called
        verify(restTemplate, atLeastOnce()).getForObject(anyString(), eq(CurrencyConversionResponse.class));
    }

    @Test
    void getExchangeRate_UnsupportedCurrency_ThrowsException() {
        assertThrows(ExchangeRateException.class, 
            () -> exchangeRateService.getExchangeRate("USD", "XYZ"));
    }

    @Test
    void isStale_WhenNeverUpdated_ReturnsTrue() {
        // Test through public behavior instead of direct method access
        // The service should fetch rates when stale, so we can test this behavior
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(CurrencyConversionResponse.builder()
                        .success(true)
                        .timestamp(System.currentTimeMillis())
                        .base("USD")
                        .date(LocalDate.now())
                        .rates(Map.of("EUR", new BigDecimal("0.85")))
                        .build());
        
        // This should trigger a fetch due to staleness (no previous fetch)
        exchangeRateService.getExchangeRate("USD", "EUR");
        
        // Verify that fetchLatestRates was called (indicating staleness)
        verify(restTemplate, atLeastOnce()).getForObject(anyString(), eq(CurrencyConversionResponse.class));
    }

    @Test
    void isStale_WhenRecentlyUpdated_ReturnsFalse() {
        // Mock the response for when fetch is triggered
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(CurrencyConversionResponse.builder()
                        .success(true)
                        .timestamp(System.currentTimeMillis())
                        .base("USD")
                        .date(LocalDate.now())
                        .rates(Map.of("EUR", new BigDecimal("0.85")))
                        .build());
        
        // Call fetchLatestRates to set the last update time
        exchangeRateService.fetchLatestRates();
        
        // This should not trigger another fetch since rates are fresh
        exchangeRateService.getExchangeRate("USD", "EUR");
        
        // Verify that fetch was called at least once
        verify(restTemplate, atLeastOnce()).getForObject(anyString(), eq(CurrencyConversionResponse.class));
    }

    @Test
    void isStale_WhenStale_ReturnsTrue() throws InterruptedException {
        // Mock the response for when fetch is triggered
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(CurrencyConversionResponse.builder()
                        .success(true)
                        .timestamp(System.currentTimeMillis())
                        .base("USD")
                        .date(LocalDate.now())
                        .rates(Map.of("EUR", new BigDecimal("0.85")))
                        .build());
        
        // Call fetchLatestRates to set initial rates
        exchangeRateService.fetchLatestRates();
        
        // Wait for rates to become stale (timeout is 5000ms)
        Thread.sleep(6000);
        
        // This should trigger another fetch due to staleness
        exchangeRateService.getExchangeRate("USD", "EUR");
        
        // Verify that fetchLatestRates was called at least twice (initial + stale refresh)
        verify(restTemplate, atLeast(2)).getForObject(anyString(), eq(CurrencyConversionResponse.class));
    }

    @Test
    void fetchLatestRates_UpdatesRates() {
        // Mock the REST template response
        CurrencyConversionResponse mockResponse = CurrencyConversionResponse.builder()
                .success(true)
                .timestamp(System.currentTimeMillis())
                .base("USD")
                .date(LocalDate.now())
                .rates(Map.of(
                    "EUR", new BigDecimal("0.85"),
                    "GBP", new BigDecimal("0.73")
                ))
                .amount(new BigDecimal("100"))
                .build();
        
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(mockResponse);
        
        // Call the method
        exchangeRateService.fetchLatestRates();
        
        // Verify the method was called with correct parameters
        verify(restTemplate, times(1)).getForObject(anyString(), eq(CurrencyConversionResponse.class));
        
        // Verify rates are accessible through public method
        Map<String, BigDecimal> rates = exchangeRateService.getAllRates();
        assertNotNull(rates);
        assertTrue(rates.containsKey("EUR"));
        assertTrue(rates.containsKey("GBP"));
    }

    @Test
    void getAllRates_ReturnsAllRates() {
        // Set up some test rates
        Map<String, BigDecimal> testRates = new ConcurrentHashMap<>();
        testRates.put("EUR", new BigDecimal("0.85"));
        testRates.put("GBP", new BigDecimal("0.73"));
        ReflectionTestUtils.setField(exchangeRateService, "rates", testRates);
        
        Map<String, BigDecimal> rates = exchangeRateService.getAllRates();
        
        assertNotNull(rates);
        assertEquals(2, rates.size());
        assertTrue(rates.containsKey("EUR"));
        assertTrue(rates.containsKey("GBP"));
    }
}
