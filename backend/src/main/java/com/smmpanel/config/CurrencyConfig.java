package com.smmpanel.config;

import com.smmpanel.service.CurrencyService;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration class for currency-related settings and beans
 */
@Configuration
@EnableScheduling
public class CurrencyConfig {

    /**
     * Configuration properties for currency settings
     */
    @Bean
    @ConfigurationProperties(prefix = "app.currency")
    public CurrencyProperties currencyProperties() {
        return new CurrencyProperties();
    }



    /**
     * Currency configuration properties
     */
    @Data
    public static class CurrencyProperties {
        private String defaultCurrency = "USD";
        private ExchangeRateApiProperties exchangeRateApi = new ExchangeRateApiProperties();
        
        // Manual getters since Lombok annotation processing is broken
        public String getDefaultCurrency() { return defaultCurrency; }
        public ExchangeRateApiProperties getExchangeRateApi() { return exchangeRateApi; }
        
        @Data
        public static class ExchangeRateApiProperties {
            private String url = "https://api.exchangerate.host/latest";
            private String baseCurrency = "USD";
            private long refreshRate = 3600000; // 1 hour in milliseconds
            
            // Manual getters since Lombok annotation processing is broken
            public String getUrl() { return url; }
            public String getBaseCurrency() { return baseCurrency; }
            public long getRefreshRate() { return refreshRate; }
        }
    }
}
