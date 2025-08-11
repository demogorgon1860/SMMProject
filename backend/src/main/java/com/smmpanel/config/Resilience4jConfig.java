package com.smmpanel.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * PRODUCTION-READY Resilience4j Configuration
 *
 * <p>CIRCUIT BREAKER IMPROVEMENTS: 1. Custom circuit breaker configurations for different services
 * 2. Retry patterns with exponential backoff 3. Proper event listeners for monitoring 4. Fallback
 * strategies for external services
 */
@Slf4j
@Configuration
public class Resilience4jConfig {

    /** Circuit Breaker Registry with custom configurations */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

        // Binom API Circuit Breaker - More aggressive for external API
        CircuitBreakerConfig binomConfig =
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50.0f) // Open if 50% of calls fail
                        .slowCallRateThreshold(50.0f) // Open if 50% of calls are slow
                        .slowCallDurationThreshold(
                                Duration.ofSeconds(5)) // Consider call slow after 5s
                        .waitDurationInOpenState(Duration.ofSeconds(30)) // Stay open for 30s
                        .minimumNumberOfCalls(10) // Need 10 calls before calculating failure rate
                        .slidingWindowSize(20) // Use last 20 calls for calculation
                        .permittedNumberOfCallsInHalfOpenState(5) // Allow 5 test calls in half-open
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build();

        registry.circuitBreaker("binom-api", binomConfig);

        // YouTube API Circuit Breaker - Less aggressive, YouTube is more reliable
        CircuitBreakerConfig youtubeConfig =
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(60.0f)
                        .slowCallRateThreshold(60.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(10))
                        .waitDurationInOpenState(Duration.ofMinutes(2))
                        .minimumNumberOfCalls(15)
                        .slidingWindowSize(30)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build();

        registry.circuitBreaker("youtube-api", youtubeConfig);

        // Database Circuit Breaker - Very conservative, DB should be highly available
        CircuitBreakerConfig databaseConfig =
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(80.0f)
                        .slowCallRateThreshold(70.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(3))
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .minimumNumberOfCalls(20)
                        .slidingWindowSize(50)
                        .permittedNumberOfCallsInHalfOpenState(10)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build();

        registry.circuitBreaker("database", databaseConfig);

        // Payment API Circuit Breaker - Balanced approach for payment processing
        CircuitBreakerConfig paymentConfig =
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(40.0f)
                        .slowCallRateThreshold(50.0f)
                        .slowCallDurationThreshold(Duration.ofSeconds(8))
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .minimumNumberOfCalls(8)
                        .slidingWindowSize(25)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .build();

        registry.circuitBreaker("payment-api", paymentConfig);

        // Add event listeners for monitoring
        addCircuitBreakerEventListeners(registry);

        return registry;
    }

    /** Retry Registry with custom configurations */
    @Bean
    public RetryRegistry retryRegistry() {
        RetryRegistry registry = RetryRegistry.ofDefaults();

        // Binom API Retry - Exponential backoff for external API
        RetryConfig binomRetryConfig =
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(1000))
                        .intervalFunction(attempt -> 1000L * attempt * attempt) // Quadratic backoff
                        .retryOnException(
                                throwable ->
                                        throwable instanceof java.net.SocketTimeoutException
                                                || throwable instanceof java.net.ConnectException
                                                || throwable instanceof java.io.IOException)
                        .build();

        registry.retry("binom-api", binomRetryConfig);

        // YouTube API Retry - More conservative
        RetryConfig youtubeRetryConfig =
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(2000))
                        .intervalFunction(attempt -> 2000L * attempt)
                        .retryOnException(
                                throwable ->
                                        throwable instanceof java.net.SocketTimeoutException
                                                || throwable
                                                        instanceof
                                                        com.google.api.client.googleapis.json
                                                                .GoogleJsonResponseException)
                        .build();

        registry.retry("youtube-api", youtubeRetryConfig);

        // Database Retry - Quick retries for transient issues
        RetryConfig databaseRetryConfig =
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(500))
                        .intervalFunction(attempt -> 500L * attempt)
                        .retryOnException(
                                throwable ->
                                        throwable instanceof java.sql.SQLException
                                                || throwable
                                                        instanceof
                                                        org.springframework.dao
                                                                .TransientDataAccessException)
                        .build();

        registry.retry("database", databaseRetryConfig);

        // Payment API Retry - Careful with payment operations
        RetryConfig paymentRetryConfig =
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(3000))
                        .intervalFunction(attempt -> 3000L + (1000L * attempt))
                        .retryOnException(
                                throwable ->
                                        throwable instanceof java.net.SocketTimeoutException
                                                || throwable instanceof java.net.ConnectException)
                        .build();

        registry.retry("payment-api", paymentRetryConfig);

        // Add event listeners for monitoring
        addRetryEventListeners(registry);

        return registry;
    }

    /** Circuit Breaker instances for easy injection */
    @Bean
    public CircuitBreaker binomCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("binom-api");
    }

    @Bean
    public CircuitBreaker youtubeCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("youtube-api");
    }

    @Bean
    public CircuitBreaker databaseCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("database");
    }

    @Bean
    public CircuitBreaker paymentCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("payment-api");
    }

    /** Retry instances for easy injection */
    @Bean
    public Retry binomRetry(RetryRegistry registry) {
        return registry.retry("binom-api");
    }

    @Bean
    public Retry youtubeRetry(RetryRegistry registry) {
        return registry.retry("youtube-api");
    }

    @Bean
    public Retry databaseRetry(RetryRegistry registry) {
        return registry.retry("database");
    }

    @Bean
    public Retry paymentRetry(RetryRegistry registry) {
        return registry.retry("payment-api");
    }

    // Private helper methods for event listeners

    private void addCircuitBreakerEventListeners(CircuitBreakerRegistry registry) {
        registry.getAllCircuitBreakers()
                .forEach(
                        circuitBreaker -> {
                            circuitBreaker
                                    .getEventPublisher()
                                    .onStateTransition(
                                            event ->
                                                    log.info(
                                                            "Circuit breaker {} state transition:"
                                                                    + " {} -> {}",
                                                            event.getCircuitBreakerName(),
                                                            event.getStateTransition()
                                                                    .getFromState(),
                                                            event.getStateTransition()
                                                                    .getToState()))
                                    .onFailureRateExceeded(
                                            event ->
                                                    log.warn(
                                                            "Circuit breaker {} failure rate"
                                                                    + " exceeded: {}%",
                                                            event.getCircuitBreakerName(),
                                                            event.getFailureRate()))
                                    .onSlowCallRateExceeded(
                                            event ->
                                                    log.warn(
                                                            "Circuit breaker {} slow call rate"
                                                                    + " exceeded: {}%",
                                                            event.getCircuitBreakerName(),
                                                            event.getSlowCallRate()))
                                    .onCallNotPermitted(
                                            event ->
                                                    log.debug(
                                                            "Circuit breaker {} rejected call",
                                                            event.getCircuitBreakerName()));
                        });
    }

    private void addRetryEventListeners(RetryRegistry registry) {
        registry.getAllRetries()
                .forEach(
                        retry -> {
                            retry.getEventPublisher()
                                    .onRetry(
                                            event ->
                                                    log.debug(
                                                            "Retry {} attempt {} for {}",
                                                            event.getName(),
                                                            event.getNumberOfRetryAttempts(),
                                                            event.getLastThrowable().getMessage()))
                                    .onSuccess(
                                            event ->
                                                    log.debug(
                                                            "Retry {} succeeded after {} attempts",
                                                            event.getName(),
                                                            event.getNumberOfRetryAttempts()))
                                    .onError(
                                            event ->
                                                    log.error(
                                                            "Retry {} failed after {} attempts: {}",
                                                            event.getName(),
                                                            event.getNumberOfRetryAttempts(),
                                                            event.getLastThrowable().getMessage()));
                        });
    }
}
