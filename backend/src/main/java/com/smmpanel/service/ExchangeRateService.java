package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.exception.ExchangeRateException;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Service for managing and caching exchange rates */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    @org.springframework.beans.factory.annotation.Qualifier("exchangeRateRestTemplate") private final RestTemplate restTemplate;

    private final CurrencyService currencyService;

    @org.springframework.beans.factory.annotation.Qualifier("exchangeRateCircuitBreaker") private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;

    @org.springframework.beans.factory.annotation.Qualifier("exchangeRateRetry") private final io.github.resilience4j.retry.Retry retry;

    @Value("${app.currency.exchange-rate-api.url:https://api.exchangerate.host/latest}")
    private String exchangeRateApiUrl;

    @Value("${app.currency.exchange-rate-api.base-currency:USD}")
    private String baseCurrency;

    @Value("${app.currency.exchange-rate-api.timeout-ms:5000}")
    private long apiTimeoutMs;

    private final AtomicReference<Instant> lastUpdateTime = new AtomicReference<>(Instant.now());
    private final Map<String, BigDecimal> rates = new ConcurrentHashMap<>();

    /** Initialize with base currency rate */
    @PostConstruct
    public void init() {
        rates.put(baseCurrency, BigDecimal.ONE);
        fetchLatestRates();
    }

    /** Get the current exchange rate for a currency pair */
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
            throw new ExchangeRateException(
                    "Exchange rate not available for one or more currencies");
        }

        // Convert from fromCurrency to base currency, then to toCurrency
        return toRate.divide(fromRate, 10, BigDecimal.ROUND_HALF_UP);
    }

    /** Fetch the latest exchange rates from the API */
    @Scheduled(fixedRateString = "${app.currency.exchange-rate-api.refresh-rate:3600000}")
    @CacheEvict(value = "exchangeRates", allEntries = true)
    public void fetchLatestRates() {
        try {
            CurrencyConversionResponse response =
                    circuitBreaker.executeSupplier(
                            () ->
                                    retry.executeSupplier(
                                            () -> {
                                                String url =
                                                        UriComponentsBuilder.fromHttpUrl(
                                                                        exchangeRateApiUrl)
                                                                .queryParam("base", baseCurrency)
                                                                .queryParam(
                                                                        "symbols",
                                                                        String.join(
                                                                                ",",
                                                                                currencyService
                                                                                        .getSupportedCurrencies()
                                                                                        .keySet()))
                                                                .build()
                                                                .toUriString();

                                                log.info(
                                                        "Fetching latest exchange rates from: {}",
                                                        url);

                                                return restTemplate.getForObject(
                                                        url, CurrencyConversionResponse.class);
                                            }));

            if (response != null && response.isSuccess() && response.getRates() != null) {
                rates.clear();
                rates.putAll(response.getRates());
                lastUpdateTime.set(Instant.now());
                log.info(
                        "Successfully updated exchange rates for {} currencies",
                        response.getRates().size());
            } else {
                log.error(
                        "Failed to fetch exchange rates: {}",
                        response != null ? response.getError() : "Unknown error");
                fallbackToDefaultRates();
            }
        } catch (Exception e) {
            log.error("Error fetching exchange rates, using fallback: {}", e.getMessage(), e);
            fallbackToDefaultRates();
        }
    }

    private void fallbackToDefaultRates() {
        if (rates.isEmpty()) {
            log.warn("No exchange rates available, using default 1:1 rates");
            currencyService
                    .getSupportedCurrencies()
                    .keySet()
                    .forEach(currency -> rates.put(currency, BigDecimal.ONE));
            lastUpdateTime.set(Instant.now());
        } else {
            log.info("Keeping existing exchange rates as fallback");
        }
    }

    /** Check if the rates are stale and need to be refreshed */
    private boolean isStale() {
        Instant lastUpdate = lastUpdateTime.get();
        return lastUpdate == null
                || Duration.between(lastUpdate, Instant.now()).toMillis() > apiTimeoutMs;
    }

    /** Validate that a currency code is supported */
    private void validateCurrencyCode(String currencyCode) {
        if (!currencyService.getSupportedCurrencies().containsKey(currencyCode)) {
            throw new ExchangeRateException("Unsupported currency: " + currencyCode);
        }
    }

    /** Get the last time the rates were updated */
    public Instant getLastUpdateTime() {
        return lastUpdateTime.get();
    }

    /** Get all current exchange rates */
    public Map<String, BigDecimal> getAllRates() {
        if (isStale()) {
            fetchLatestRates();
        }
        return new ConcurrentHashMap<>(rates);
    }
}
