package com.smmpanel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.util.LoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LoggingConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;

    public LoggingConfig(@org.springframework.beans.factory.annotation.Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loggingInterceptor());
    }

    @Bean
    public LoggingInterceptor loggingInterceptor() {
        return new LoggingInterceptor(objectMapper);
    }
}
