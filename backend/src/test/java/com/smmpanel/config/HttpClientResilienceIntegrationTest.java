package com.smmpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest
@TestPropertySource(
        properties = {
            "app.cryptomus.api.url=https://test.example.com",
            "app.binom.api.url=https://test.example.com",
            "app.currency.exchange-rate-api.url=https://test.example.com"
        })
class HttpClientResilienceIntegrationTest {

    @Autowired
    @Qualifier("cryptomusRestTemplate") private RestTemplate cryptomusRestTemplate;

    @Autowired
    @Qualifier("binomRestTemplate") private RestTemplate binomRestTemplate;

    @Autowired
    @Qualifier("exchangeRateRestTemplate") private RestTemplate exchangeRateRestTemplate;

    @Autowired
    @Qualifier("cryptomusWebClient") private WebClient cryptomusWebClient;

    @Autowired
    @Qualifier("binomWebClient") private WebClient binomWebClient;

    @Autowired
    @Qualifier("exchangeRateWebClient") private WebClient exchangeRateWebClient;

    @Autowired
    @Qualifier("cryptomusCircuitBreaker") private CircuitBreaker cryptomusCircuitBreaker;

    @Autowired
    @Qualifier("binomCircuitBreaker") private CircuitBreaker binomCircuitBreaker;

    @Autowired
    @Qualifier("exchangeRateCircuitBreaker") private CircuitBreaker exchangeRateCircuitBreaker;

    @Autowired
    @Qualifier("cryptomusReadRetry") private Retry cryptomusReadRetry;

    @Autowired
    @Qualifier("cryptomusWriteRetry") private Retry cryptomusWriteRetry;

    @Autowired
    @Qualifier("binomReadRetry") private Retry binomReadRetry;

    @Autowired
    @Qualifier("binomWriteRetry") private Retry binomWriteRetry;

    @Autowired
    @Qualifier("exchangeRateRetry") private Retry exchangeRateRetry;

    @Test
    void testRestTemplateBeansAreConfigured() {
        assertNotNull(cryptomusRestTemplate, "Cryptomus RestTemplate should be configured");
        assertNotNull(binomRestTemplate, "Binom RestTemplate should be configured");
        assertNotNull(exchangeRateRestTemplate, "ExchangeRate RestTemplate should be configured");

        // Verify trace interceptors are configured
        assertFalse(
                cryptomusRestTemplate.getInterceptors().isEmpty(),
                "Cryptomus RestTemplate should have trace interceptors");
        assertFalse(
                binomRestTemplate.getInterceptors().isEmpty(),
                "Binom RestTemplate should have trace interceptors");
        assertFalse(
                exchangeRateRestTemplate.getInterceptors().isEmpty(),
                "ExchangeRate RestTemplate should have trace interceptors");
    }

    @Test
    void testWebClientBeansAreConfigured() {
        assertNotNull(cryptomusWebClient, "Cryptomus WebClient should be configured");
        assertNotNull(binomWebClient, "Binom WebClient should be configured");
        assertNotNull(exchangeRateWebClient, "ExchangeRate WebClient should be configured");
    }

    @Test
    void testCircuitBreakerBeansAreConfigured() {
        assertNotNull(cryptomusCircuitBreaker, "Cryptomus CircuitBreaker should be configured");
        assertNotNull(binomCircuitBreaker, "Binom CircuitBreaker should be configured");
        assertNotNull(
                exchangeRateCircuitBreaker, "ExchangeRate CircuitBreaker should be configured");

        // Verify circuit breaker states
        assertEquals(
                CircuitBreaker.State.CLOSED,
                cryptomusCircuitBreaker.getState(),
                "Circuit breaker should start in CLOSED state");
        assertEquals(
                CircuitBreaker.State.CLOSED,
                binomCircuitBreaker.getState(),
                "Circuit breaker should start in CLOSED state");
        assertEquals(
                CircuitBreaker.State.CLOSED,
                exchangeRateCircuitBreaker.getState(),
                "Circuit breaker should start in CLOSED state");
    }

    @Test
    void testRetryBeansAreConfigured() {
        assertNotNull(cryptomusReadRetry, "Cryptomus Read Retry should be configured");
        assertNotNull(cryptomusWriteRetry, "Cryptomus Write Retry should be configured");
        assertNotNull(binomReadRetry, "Binom Read Retry should be configured");
        assertNotNull(binomWriteRetry, "Binom Write Retry should be configured");
        assertNotNull(exchangeRateRetry, "ExchangeRate Retry should be configured");

        // Verify retry configurations
        assertEquals(
                5,
                cryptomusReadRetry.getRetryConfig().getMaxAttempts(),
                "Read retry should have 5 max attempts");
        assertEquals(
                1,
                cryptomusWriteRetry.getRetryConfig().getMaxAttempts(),
                "Write retry should have 1 max attempt (no retry for non-idempotent operations)");
        assertEquals(
                5,
                binomReadRetry.getRetryConfig().getMaxAttempts(),
                "Read retry should have 5 max attempts");
        assertEquals(
                1,
                binomWriteRetry.getRetryConfig().getMaxAttempts(),
                "Write retry should have 1 max attempt (no retry for non-idempotent operations)");
        assertEquals(
                5,
                exchangeRateRetry.getRetryConfig().getMaxAttempts(),
                "Exchange rate retry should have 5 max attempts (idempotent operation)");
    }

    @Test
    void testCircuitBreakerConfiguration() {
        // Test circuit breaker configuration values
        var cryptomusConfig = cryptomusCircuitBreaker.getCircuitBreakerConfig();
        assertEquals(
                60.0f,
                cryptomusConfig.getFailureRateThreshold(),
                "External API circuit breaker should have 60% failure rate threshold");
        assertEquals(
                20,
                cryptomusConfig.getSlidingWindowSize(),
                "External API circuit breaker should have sliding window size of 20");
        assertEquals(
                10,
                cryptomusConfig.getMinimumNumberOfCalls(),
                "External API circuit breaker should have minimum 10 calls");

        var exchangeRateConfig = exchangeRateCircuitBreaker.getCircuitBreakerConfig();
        assertEquals(
                60.0f,
                exchangeRateConfig.getFailureRateThreshold(),
                "External API circuit breaker should have 60% failure rate threshold");
    }

    @Test
    void testRetryConfiguration() {
        // Test retry configuration values
        var readRetryConfig = cryptomusReadRetry.getRetryConfig();
        assertEquals(
                5,
                readRetryConfig.getMaxAttempts(),
                "Read operations should have 5 retry attempts");
        assertEquals(
                1000,
                readRetryConfig.getIntervalFunction().apply(1),
                "Read retry should have 1000ms base interval");

        var writeRetryConfig = cryptomusWriteRetry.getRetryConfig();
        assertEquals(
                1,
                writeRetryConfig.getMaxAttempts(),
                "Write operations should have no retry (1 attempt total)");
    }
}
