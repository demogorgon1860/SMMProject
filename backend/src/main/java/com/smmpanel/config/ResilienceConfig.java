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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Slf4j
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig =
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .recordExceptions(
                                ResourceAccessException.class,
                                HttpServerErrorException.class,
                                RuntimeException.class)
                        .ignoreExceptions(
                                HttpClientErrorException.BadRequest.class,
                                HttpClientErrorException.Unauthorized.class,
                                HttpClientErrorException.Forbidden.class,
                                HttpClientErrorException.NotFound.class)
                        .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);

        CircuitBreakerConfig externalApiConfig =
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(60)
                        .waitDurationInOpenState(Duration.ofMinutes(1))
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .permittedNumberOfCallsInHalfOpenState(5)
                        .recordExceptions(
                                ResourceAccessException.class,
                                HttpServerErrorException.class,
                                RuntimeException.class)
                        .ignoreExceptions(
                                HttpClientErrorException.BadRequest.class,
                                HttpClientErrorException.Unauthorized.class,
                                HttpClientErrorException.Forbidden.class,
                                HttpClientErrorException.NotFound.class)
                        .build();

        registry.circuitBreaker("cryptomus", externalApiConfig);
        registry.circuitBreaker("binom", externalApiConfig);
        registry.circuitBreaker("exchangeRate", externalApiConfig);

        return registry;
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig defaultConfig =
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(500))
                        .retryExceptions(
                                ResourceAccessException.class,
                                HttpServerErrorException.InternalServerError.class,
                                HttpServerErrorException.BadGateway.class,
                                HttpServerErrorException.ServiceUnavailable.class,
                                HttpServerErrorException.GatewayTimeout.class)
                        .ignoreExceptions(
                                HttpClientErrorException.BadRequest.class,
                                HttpClientErrorException.Unauthorized.class,
                                HttpClientErrorException.Forbidden.class,
                                HttpClientErrorException.NotFound.class,
                                HttpClientErrorException.Conflict.class,
                                HttpClientErrorException.UnprocessableEntity.class)
                        .build();

        RetryRegistry registry = RetryRegistry.of(defaultConfig);

        RetryConfig idempotentConfig =
                RetryConfig.custom()
                        .maxAttempts(5)
                        .waitDuration(Duration.ofMillis(1000))
                        .retryExceptions(
                                ResourceAccessException.class,
                                HttpServerErrorException.InternalServerError.class,
                                HttpServerErrorException.BadGateway.class,
                                HttpServerErrorException.ServiceUnavailable.class,
                                HttpServerErrorException.GatewayTimeout.class)
                        .ignoreExceptions(
                                HttpClientErrorException.BadRequest.class,
                                HttpClientErrorException.Unauthorized.class,
                                HttpClientErrorException.Forbidden.class,
                                HttpClientErrorException.NotFound.class,
                                HttpClientErrorException.Conflict.class,
                                HttpClientErrorException.UnprocessableEntity.class)
                        .build();

        registry.retry("exchangeRate", idempotentConfig);
        registry.retry("binomRead", idempotentConfig);
        registry.retry("cryptomusRead", idempotentConfig);

        RetryConfig nonIdempotentConfig = RetryConfig.custom().maxAttempts(1).build();

        registry.retry("binomWrite", nonIdempotentConfig);
        registry.retry("cryptomusWrite", nonIdempotentConfig);

        return registry;
    }

    @Bean
    public CircuitBreaker cryptomusCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker("cryptomus");
        circuitBreaker
                .getEventPublisher()
                .onStateTransition(
                        event -> log.info("Cryptomus Circuit Breaker state transition: {}", event));
        return circuitBreaker;
    }

    @Bean
    public CircuitBreaker binomCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker("binom");
        circuitBreaker
                .getEventPublisher()
                .onStateTransition(
                        event -> log.info("Binom Circuit Breaker state transition: {}", event));
        return circuitBreaker;
    }

    @Bean
    public CircuitBreaker exchangeRateCircuitBreaker(CircuitBreakerRegistry registry) {
        CircuitBreaker circuitBreaker = registry.circuitBreaker("exchangeRate");
        circuitBreaker
                .getEventPublisher()
                .onStateTransition(
                        event ->
                                log.info(
                                        "Exchange Rate Circuit Breaker state transition: {}",
                                        event));
        return circuitBreaker;
    }

    @Bean
    public Retry exchangeRateRetry(RetryRegistry registry) {
        Retry retry = registry.retry("exchangeRate");
        retry.getEventPublisher()
                .onRetry(
                        event ->
                                log.warn(
                                        "Exchange Rate API retry attempt {}: {}",
                                        event.getNumberOfRetryAttempts(),
                                        event.getLastThrowable().getMessage()));
        return retry;
    }

    @Bean
    public Retry binomReadRetry(RetryRegistry registry) {
        Retry retry = registry.retry("binomRead");
        retry.getEventPublisher()
                .onRetry(
                        event ->
                                log.warn(
                                        "Binom Read API retry attempt {}: {}",
                                        event.getNumberOfRetryAttempts(),
                                        event.getLastThrowable().getMessage()));
        return retry;
    }

    @Bean
    public Retry cryptomusReadRetry(RetryRegistry registry) {
        Retry retry = registry.retry("cryptomusRead");
        retry.getEventPublisher()
                .onRetry(
                        event ->
                                log.warn(
                                        "Cryptomus Read API retry attempt {}: {}",
                                        event.getNumberOfRetryAttempts(),
                                        event.getLastThrowable().getMessage()));
        return retry;
    }

    @Bean
    public Retry binomWriteRetry(RetryRegistry registry) {
        Retry retry = registry.retry("binomWrite");
        retry.getEventPublisher()
                .onRetry(
                        event ->
                                log.warn(
                                        "Binom Write API retry attempt {}: {}",
                                        event.getNumberOfRetryAttempts(),
                                        event.getLastThrowable().getMessage()));
        return retry;
    }

    @Bean
    public Retry cryptomusWriteRetry(RetryRegistry registry) {
        Retry retry = registry.retry("cryptomusWrite");
        retry.getEventPublisher()
                .onRetry(
                        event ->
                                log.warn(
                                        "Cryptomus Write API retry attempt {}: {}",
                                        event.getNumberOfRetryAttempts(),
                                        event.getLastThrowable().getMessage()));
        return retry;
    }
}
