package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.entity.User;
import com.smmpanel.exception.CurrencyConversionException;
import com.smmpanel.repository.UserRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling currency conversion and formatting
 */
@Slf4j
@Service
@RequiredArgsConstructor
@CacheConfig(cacheNames = "exchangeRates")
public class CurrencyService {

    @Value("${app.currency.default:USD}")
    private String defaultCurrency;

    @Value("${app.currency.exchange-rate-api.url:https://api.exchangerate.host/latest}")
    private String exchangeRateApiUrl;

    @Value("${app.currency.exchange-rate-api.base-currency:USD}")
    private String baseCurrency;

    private final RestTemplate restTemplate = new RestTemplate();
    private final UserRepository userRepository;

    // In-memory cache for exchange rates
    private final Map<String, BigDecimal> exchangeRates = new ConcurrentHashMap<>();
    
    // Supported currencies with their display properties
    private static final Map<String, CurrencyInfo> SUPPORTED_CURRENCIES = Map.of(
        "USD", new CurrencyInfo("$", 2, "United States Dollar"),
        "EUR", new CurrencyInfo("€", 2, "Euro"),
        "GBP", new CurrencyInfo("£", 2, "British Pound"),
        "JPY", new CurrencyInfo("¥", 0, "Japanese Yen"),
        "BTC", new CurrencyInfo("₿", 8, "Bitcoin"),
        "ETH", new CurrencyInfo("Ξ", 6, "Ethereum"),
        "RUB", new CurrencyInfo("₽", 2, "Russian Ruble"),
        "UAH", new CurrencyInfo("₴", 2, "Ukrainian Hryvnia")
    );

    /**
     * Get the user's preferred currency or fall back to default
     */
    public String getUserCurrency(Long userId) {
        return userRepository.findById(userId)
                .map(User::getPreferredCurrency)
                .orElse(defaultCurrency);
    }

    /**
     * Convert amount from one currency to another
     */
    public BigDecimal convertCurrency(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return amount.setScale(getDecimalPlaces(toCurrency), RoundingMode.HALF_UP);
        }

        BigDecimal fromRate = getExchangeRate(fromCurrency);
        BigDecimal toRate = getExchangeRate(toCurrency);

        // Convert to base currency first, then to target currency
        BigDecimal amountInBase = amount.divide(fromRate, 10, RoundingMode.HALF_UP);
        BigDecimal convertedAmount = amountInBase.multiply(toRate);
        
        return convertedAmount.setScale(getDecimalPlaces(toCurrency), RoundingMode.HALF_UP);
    }

    /**
     * Format amount with currency symbol and proper decimal places
     */
    public String formatCurrency(BigDecimal amount, String currencyCode) {
        int decimalPlaces = getDecimalPlaces(currencyCode);
        String symbol = getCurrencySymbol(currencyCode);
        boolean symbolAfterAmount = isSymbolAfterAmount(currencyCode);
        
        // Format with proper decimal places and grouping
        String formatPattern = "%,." + decimalPlaces + "f";
        String formattedAmount = String.format(Locale.US, formatPattern, amount);
        
        // Add currency symbol based on locale
        return symbolAfterAmount ? formattedAmount + " " + symbol : symbol + formattedAmount;
    }

    /**
     * Get exchange rate for a currency relative to the base currency
     */
    @Cacheable(key = "#currencyCode")
    public BigDecimal getExchangeRate(String currencyCode) {
        if (currencyCode.equals(baseCurrency)) {
            return BigDecimal.ONE;
        }
        
        if (exchangeRates.containsKey(currencyCode)) {
            return exchangeRates.get(currencyCode);
        }
        
        // If not in cache, fetch from API (this should be rare as we have a scheduled update)
        log.warn("Exchange rate for {} not in cache, fetching from API", currencyCode);
        fetchLatestRates();
        
        return exchangeRates.getOrDefault(currencyCode, BigDecimal.ONE);
    }

    /**
     * Fetch latest exchange rates from the API
     */
    @Scheduled(fixedRateString = "${app.currency.exchange-rate-api.refresh-rate:3600000}") // Default: 1 hour
    @CacheEvict(allEntries = true)
    public void fetchLatestRates() {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(exchangeRateApiUrl)
                    .queryParam("base", baseCurrency)
                    .queryParam("symbols", String.join(",", SUPPORTED_CURRENCIES.keySet()))
                    .toUriString();

            CurrencyConversionResponse response = restTemplate.getForObject(url, CurrencyConversionResponse.class);
            
            if (response != null && response.getRates() != null) {
                exchangeRates.clear();
                exchangeRates.putAll(response.getRates());
                log.info("Updated exchange rates for {} currencies", exchangeRates.size());
            }
        } catch (Exception e) {
            log.error("Failed to fetch exchange rates: {}", e.getMessage());
            // Keep using old rates if available
            if (exchangeRates.isEmpty()) {
                // If no rates available, use 1:1 as fallback
                SUPPORTED_CURRENCIES.keySet().forEach(currency -> exchangeRates.put(currency, BigDecimal.ONE));
            }
        }
    }

    /**
     * Get all supported currencies with their display information
     */
    public Map<String, CurrencyInfo> getSupportedCurrencies() {
        return new HashMap<>(SUPPORTED_CURRENCIES);
    }

    /**
     * Get the number of decimal places to display for a currency
     */
    public int getDecimalPlaces(String currencyCode) {
        return Optional.ofNullable(SUPPORTED_CURRENCIES.get(currencyCode))
                .map(CurrencyInfo::getDecimalPlaces)
                .orElse(2);
    }

    /**
     * Get the currency symbol for a currency code
     */
    public String getCurrencySymbol(String currencyCode) {
        return Optional.ofNullable(SUPPORTED_CURRENCIES.get(currencyCode))
                .map(CurrencyInfo::getSymbol)
                .orElse(currencyCode);
    }

    /**
     * Check if the currency symbol should be placed after the amount
     */
    public boolean isSymbolAfterAmount(String currencyCode) {
        return Optional.ofNullable(SUPPORTED_CURRENCIES.get(currencyCode))
                .map(info -> info.isSymbolAfterAmount())
                .orElse(false);
    }

    /**
     * Get exchange rates for a base currency
     */
    public Map<String, BigDecimal> getExchangeRates(String base) {
        if (base.equals(baseCurrency)) {
            return exchangeRates;
        }
        
        // Convert rates to the requested base currency
        BigDecimal baseRate = getExchangeRate(base);
        Map<String, BigDecimal> convertedRates = new HashMap<>();
        
        for (Map.Entry<String, BigDecimal> entry : exchangeRates.entrySet()) {
            String currency = entry.getKey();
            BigDecimal rate = entry.getValue();
            BigDecimal convertedRate = rate.divide(baseRate, 10, RoundingMode.HALF_UP);
            convertedRates.put(currency, convertedRate);
        }
        
        return convertedRates;
    }

    /**
     * Get the default currency
     */
    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    /**
     * DTO for currency information
     */
    @Data
    @Builder
    public static class CurrencyInfo {
        private final String symbol;
        private final int decimalPlaces;
        private final String name;
        private boolean preferred;
        private boolean symbolAfterAmount;
        
        // Manual constructor since Lombok annotation processing is broken
        public CurrencyInfo(String symbol, int decimalPlaces, String name) {
            this.symbol = symbol;
            this.decimalPlaces = decimalPlaces;
            this.name = name;
            this.preferred = false;
            this.symbolAfterAmount = false;
        }
        
        public CurrencyInfo(String symbol, int decimalPlaces, String name, boolean preferred) {
            this.symbol = symbol;
            this.decimalPlaces = decimalPlaces;
            this.name = name;
            this.preferred = preferred;
            this.symbolAfterAmount = false;
        }
        
        public CurrencyInfo(String symbol, int decimalPlaces, String name, boolean preferred, boolean symbolAfterAmount) {
            this.symbol = symbol;
            this.decimalPlaces = decimalPlaces;
            this.name = name;
            this.preferred = preferred;
            this.symbolAfterAmount = symbolAfterAmount;
        }
        
        // Manual getters since Lombok annotation processing is broken
        public String getSymbol() { return symbol; }
        public int getDecimalPlaces() { return decimalPlaces; }
        public String getName() { return name; }
        public boolean isPreferred() { return preferred; }
        public void setPreferred(boolean preferred) { this.preferred = preferred; }
        public boolean isSymbolAfterAmount() { return symbolAfterAmount; }
        public void setSymbolAfterAmount(boolean symbolAfterAmount) { this.symbolAfterAmount = symbolAfterAmount; }
    }
}
