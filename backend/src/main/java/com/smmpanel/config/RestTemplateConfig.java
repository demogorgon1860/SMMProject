package com.smmpanel.config;

import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class RestTemplateConfig {

    private final ClientHttpRequestInterceptor traceIdInterceptor;
    private final ExchangeFilterFunction webClientTraceIdFilter;

    @Bean
    public RestTemplate restTemplateWithInterceptors() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setInterceptors(Collections.singletonList(traceIdInterceptor));
        return restTemplate;
    }

    @Bean
    public WebClient webClientWithFilters() {
        return WebClient.builder().filter(webClientTraceIdFilter).build();
    }
}
