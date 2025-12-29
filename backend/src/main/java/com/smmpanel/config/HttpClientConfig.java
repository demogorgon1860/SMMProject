package com.smmpanel.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {

    private final ClientHttpRequestInterceptor traceIdInterceptor;
    private final ExchangeFilterFunction webClientTraceIdFilter;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT =
            Duration.ofSeconds(10); // Increased for external APIs
    private static final Duration WRITE_TIMEOUT =
            Duration.ofSeconds(10); // Increased for external APIs
    private static final int MAX_CONNECTIONS = 200; // Increased for high concurrency
    private static final int MAX_CONNECTIONS_PER_ROUTE = 50; // Increased per route limit

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        ConnectionConfig connectionConfig =
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(MAX_CONNECTIONS)
                        .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setConnectionRequestTimeout(
                                Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build();

        CloseableHttpClient httpClient =
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(Collections.singletonList(traceIdInterceptor));

        log.info(
                "RestTemplate configured with connect timeout: {}ms, read timeout: {}ms",
                CONNECT_TIMEOUT.toMillis(),
                READ_TIMEOUT.toMillis());

        return restTemplate;
    }

    @Bean
    @Primary
    public WebClient webClient() {
        HttpClient httpClient =
                HttpClient.create()
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) CONNECT_TIMEOUT.toMillis())
                        .doOnConnected(
                                conn ->
                                        conn.addHandlerLast(
                                                        new ReadTimeoutHandler(
                                                                READ_TIMEOUT.toSeconds(),
                                                                TimeUnit.SECONDS))
                                                .addHandlerLast(
                                                        new WriteTimeoutHandler(
                                                                WRITE_TIMEOUT.toSeconds(),
                                                                TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(webClientTraceIdFilter)
                .build();
    }

    @Bean("cryptomusRestTemplate")
    public RestTemplate cryptomusRestTemplate() {
        return createCustomRestTemplate("Cryptomus");
    }

    @Bean("binomRestTemplate")
    public RestTemplate binomRestTemplate() {
        // Binom needs higher limits for bulk operations
        return createCustomRestTemplateWithLimits("Binom", 100, 30);
    }

    @Bean("exchangeRateRestTemplate")
    public RestTemplate exchangeRateRestTemplate() {
        return createCustomRestTemplate("ExchangeRate");
    }

    @Bean("cryptomusWebClient")
    public WebClient cryptomusWebClient() {
        return createCustomWebClient("Cryptomus");
    }

    @Bean("binomWebClient")
    public WebClient binomWebClient() {
        return createCustomWebClient("Binom");
    }

    @Bean("exchangeRateWebClient")
    public WebClient exchangeRateWebClient() {
        return createCustomWebClient("ExchangeRate");
    }

    @Bean("youtubeRestTemplate")
    public RestTemplate youtubeRestTemplate() {
        // YouTube API needs specific connection limits
        return createCustomRestTemplateWithLimits("YouTube", 50, 20);
    }

    @Bean("instagramBotRestTemplate")
    public RestTemplate instagramBotRestTemplate() {
        // Instagram bot needs longer timeouts for processing
        return createCustomRestTemplateWithLimits("InstagramBot", 50, 20);
    }

    private RestTemplate createCustomRestTemplateWithLimits(
            String clientName, int maxTotal, int maxPerRoute) {
        ConnectionConfig connectionConfig =
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(maxTotal)
                        .setMaxConnPerRoute(maxPerRoute)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setConnectionRequestTimeout(
                                Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build();

        CloseableHttpClient httpClient =
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .setUserAgent("SMM-Panel/" + clientName + "/1.0")
                        .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(Collections.singletonList(traceIdInterceptor));

        log.info(
                "{} RestTemplate configured with maxTotal: {}, maxPerRoute: {}, connect timeout:"
                        + " {}ms, read timeout: {}ms",
                clientName,
                maxTotal,
                maxPerRoute,
                CONNECT_TIMEOUT.toMillis(),
                READ_TIMEOUT.toMillis());

        return restTemplate;
    }

    private RestTemplate createCustomRestTemplate(String clientName) {
        ConnectionConfig connectionConfig =
                ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(MAX_CONNECTIONS)
                        .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setConnectionRequestTimeout(
                                Timeout.ofMilliseconds(CONNECT_TIMEOUT.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT.toMillis()))
                        .build();

        CloseableHttpClient httpClient =
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .setDefaultRequestConfig(requestConfig)
                        .setUserAgent("SMM-Panel/" + clientName + "/1.0")
                        .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setInterceptors(Collections.singletonList(traceIdInterceptor));

        log.info(
                "{} RestTemplate configured with connect timeout: {}ms, read timeout: {}ms",
                clientName,
                CONNECT_TIMEOUT.toMillis(),
                READ_TIMEOUT.toMillis());

        return restTemplate;
    }

    private WebClient createCustomWebClient(String clientName) {
        HttpClient httpClient =
                HttpClient.create()
                        .option(
                                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                (int) CONNECT_TIMEOUT.toMillis())
                        .doOnConnected(
                                conn ->
                                        conn.addHandlerLast(
                                                        new ReadTimeoutHandler(
                                                                READ_TIMEOUT.toSeconds(),
                                                                TimeUnit.SECONDS))
                                                .addHandlerLast(
                                                        new WriteTimeoutHandler(
                                                                WRITE_TIMEOUT.toSeconds(),
                                                                TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "SMM-Panel/" + clientName + "/1.0")
                .filter(webClientTraceIdFilter)
                .build();
    }
}
