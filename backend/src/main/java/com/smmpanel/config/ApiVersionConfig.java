package com.smmpanel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Enable URL path based versioning
        configurer.addPathPrefix("/api/v1", c -> true).addPathPrefix("/api/v2", c -> true);
    }

    // RequestMappingHandlerMapping is configured by Spring Boot's WebMvcAutoConfiguration
}
