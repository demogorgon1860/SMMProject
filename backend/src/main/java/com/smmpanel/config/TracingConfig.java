package com.smmpanel.config;

import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
public class TracingConfig {

    private static final String TRACE_ID_HEADER = "X-Trace-ID";
    private static final String TRACE_ID_MDC_KEY = "traceId";

    @Bean
    public ClientHttpRequestInterceptor traceIdInterceptor() {
        return new ClientHttpRequestInterceptor() {
            @Override
            public ClientHttpResponse intercept(
                    HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                    throws IOException {

                String traceId = getOrCreateTraceId();
                request.getHeaders().add(TRACE_ID_HEADER, traceId);

                log.debug(
                        "Adding trace ID {} to outbound request: {} {}",
                        traceId,
                        request.getMethod(),
                        request.getURI());

                return execution.execute(request, body);
            }
        };
    }

    @Bean
    public ExchangeFilterFunction webClientTraceIdFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(
                clientRequest -> {
                    String traceId = getOrCreateTraceId();

                    ClientRequest modifiedRequest =
                            ClientRequest.from(clientRequest)
                                    .header(TRACE_ID_HEADER, traceId)
                                    .build();

                    log.debug(
                            "Adding trace ID {} to WebClient request: {} {}",
                            traceId,
                            modifiedRequest.method(),
                            modifiedRequest.url());

                    return Mono.just(modifiedRequest);
                });
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
        return traceId;
    }
}
