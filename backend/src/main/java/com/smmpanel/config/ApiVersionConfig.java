package com.smmpanel.config;

import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Enable URL path based versioning
        configurer
            .addPathPrefix("/api/v1", handlerMapping -> 
                handlerMapping.getPatternParser()
                    .parse("/api/v1/**").getPatternCondition() != null)
            .addPathPrefix("/api/v2", handlerMapping -> 
                handlerMapping.getPatternParser()
                    .parse("/api/v2/**").getPatternCondition() != null);
    }

    @Bean
    public RequestMappingHandlerMapping requestMappingHandlerMapping() {
        return new RequestMappingHandlerMapping();
    }
}
