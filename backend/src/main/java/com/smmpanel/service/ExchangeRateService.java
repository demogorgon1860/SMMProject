package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.exception.ExchangeRateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for managing and caching exchange rates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private final RestTemplate restTemplate;
    private final CurrencyService currencyService;
    
    @Value("${app.currency.exchange-rate-api.url:https://api.exchangerate.host/latest}")
    private String exchangeRateApiUrl;
    
    @Value("${app.currency.exchange-rate-api.base-currency:USD}")
    private String baseCurrency;
    
    @Value("${app.currency.exchange-rate-api.timeout-ms:5000}")
    private long apiTimeoutMs;
    
    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>();
    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

    /**
     * Initialize with base currency rate
     */
    @PostConstruct
    public void init() {
        rates.put(baseCurrency, BigDecimal.ONE);
        fetchLatestRates();
    }

    /**
     * Get the current exchange rate for a currency pair
     */
    @Cacheable(value = "exchangeRates", key = "#fromCurrency + '_' + #toCurrency")
    public BigDecimal getExchangeRate(String fromCurrency, String toCurrency) {
        validateCurrencyCode(fromCurrency);
        validateCurrencyCode(toCurrency);
        
        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }
        
        // If we don't have fresh rates, fetch them
        if (isStale()) {
            fetchLatestRates();
        }
        
        BigDecimal fromRate = rates.getOrDefault(fromCurrency, BigDecimal.ONE);
        BigDecimal toRate = rates.getOrDefault(toCurrency, BigDecimal.ONE);
        
        if (fromRate == null || toRate == null) {
            throw new ExchangeRateException("Exchange rate not available for one or more currencies");
        }
        
        // Convert from fromCurrency to base currency, then to toCurrency
        return toRate.divide(fromRate, 10, BigDecimal.ROUND_HALF_UP);
    }
    
    /**
     * Fetch the latest exchange rates from the API
     */
    @Scheduled(fixedRateString = "${app.currency.exchange-rate-api.refresh-rate:3600000}")
    @CacheEvict(value = "exchangeRates", allEntries = true)
    public void fetchLatestRates() {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(exchangeRateApiUrl)
                    .queryParam("base", baseCurrency)
                    .queryParam("symbols", String.join(",", currencyService.getSupportedCurrencies().keySet()))
                    .build()
                    .toUriString();
            
            log.info("Fetching latest exchange rates from: {}", url);
            
            CurrencyConversionResponse response = restTemplate.getForObject(url, CurrencyConversionResponse.class);
            
            if (response != null && response.isSuccess() && response.getRates() != null) {
                rates.clear();
                rates.putAll(response.getRates());
                lastUpdateTime.set(Instant.now());
                log.info("Successfully updated exchange rates for {} currencies", response.getRates().size());
            } else {
                log.error("Failed to fetch exchange rates: {}", response != null ? response.getError() : "Unknown error");
            }
        } catch (RestClientException e) {
            log.error("Error fetching exchange rates: {}", e.getMessage(), e);
            throw new ExchangeRateException("Failed to fetch exchange rates: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if the rates are stale and need to be refreshed
     */
    private boolean isStale() {
        Instant lastUpdate = lastUpdateTime.get();
        return lastUpdate == null || 
               Duration.between(lastUpdate, Instant.now()).toMillis() > apiTimeoutMs;
    }
    
    /**
     * Validate that a currency code is supported
     */
    private void validateCurrencyCode(String currencyCode) {
        if (!currencyService.getSupportedCurrencies().containsKey(currencyCode)) {
            throw new ExchangeRateException("Unsupported currency: " + currencyCode);
        }
    }
    
    /**
     * Get the last time the rates were updated
     */
    public Instant getLastUpdateTime() {
        return lastUpdateTime.get();
    }
    
    /**
     * Get all current exchange rates
     */
    public Map<String, BigDecimal> getAllRates() {
        if (isStale()) {
            fetchLatestRates();
        }
        return new ConcurrentHashMap<>(rates);
    }
}
