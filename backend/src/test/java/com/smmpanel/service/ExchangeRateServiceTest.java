package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.exception.ExchangeRateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private CurrencyService currencyService;

    @InjectMocks
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
        CurrencyConversionResponse mockResponse = new CurrencyConversionResponse();
        mockResponse.setSuccess(true);
        mockResponse.setRates(Map.of(
            "EUR", new BigDecimal("0.85"),
            "GBP", new BigDecimal("0.73")
        ));
        
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(mockResponse);
        
        // Test conversion
        BigDecimal rate = exchangeRateService.getExchangeRate("USD", "EUR");
        assertNotNull(rate);
        assertEquals(0, new BigDecimal("0.85").compareTo(rate));
        
        // Test reverse conversion
        BigDecimal reverseRate = exchangeRateService.getExchangeRate("EUR", "USD");
        assertTrue(reverseRate.compareTo(BigDecimal.ONE) > 0);
    }

    @Test
    void getExchangeRate_ApiError_ThrowsException() {
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenThrow(new RuntimeException("API Error"));
        
        assertThrows(ExchangeRateException.class, 
            () -> exchangeRateService.getExchangeRate("USD", "EUR"));
    }

    @Test
    void getExchangeRate_UnsupportedCurrency_ThrowsException() {
        assertThrows(ExchangeRateException.class, 
            () -> exchangeRateService.getExchangeRate("USD", "XYZ"));
    }

    @Test
    void isStale_WhenNeverUpdated_ReturnsTrue() {
        assertTrue(exchangeRateService.isStale());
    }

    @Test
    void isStale_WhenRecentlyUpdated_ReturnsFalse() {
        // Set last update time to now
        ReflectionTestUtils.setField(exchangeRateService, "lastUpdateTime", Instant.now());
        assertFalse(exchangeRateService.isStale());
    }

    @Test
    void isStale_WhenStale_ReturnsTrue() throws InterruptedException {
        // Set last update time to just before the timeout
        ReflectionTestUtils.setField(exchangeRateService, "lastUpdateTime", 
            Instant.now().minus(Duration.ofMillis(6000)));
        ReflectionTestUtils.setField(exchangeRateService, "apiTimeoutMs", 5000L);
        
        assertTrue(exchangeRateService.isStale());
    }

    @Test
    void fetchLatestRates_UpdatesRates() {
        // Mock the REST template response
        CurrencyConversionResponse mockResponse = new CurrencyConversionResponse();
        mockResponse.setSuccess(true);
        mockResponse.setRates(Map.of(
            "EUR", new BigDecimal("0.85"),
            "GBP", new BigDecimal("0.73")
        ));
        
        when(restTemplate.getForObject(anyString(), eq(CurrencyConversionResponse.class)))
                .thenReturn(mockResponse);
        
        // Call the method
        exchangeRateService.fetchLatestRates();
        
        // Verify rates were updated
        Map<String, BigDecimal> rates = (Map<String, BigDecimal>) 
            ReflectionTestUtils.getField(exchangeRateService, "rates");
        
        assertNotNull(rates);
        assertFalse(rates.isEmpty());
        assertTrue(rates.containsKey("EUR"));
        assertTrue(rates.containsKey("GBP"));
        
        // Verify last update time was set
        Instant lastUpdateTime = (Instant) 
            ReflectionTestUtils.getField(exchangeRateService, "lastUpdateTime");
        assertNotNull(lastUpdateTime);
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
