package com.smmpanel.config;

import com.smmpanel.filter.UserContextMdcInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    private final UserContextMdcInterceptor userContextMdcInterceptor;

    public WebConfig(UserContextMdcInterceptor userContextMdcInterceptor) {
        this.userContextMdcInterceptor = userContextMdcInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Pushes userId/username into the SLF4J MDC for the duration of every request that
        // carries an authenticated principal. The JSON encoder picks them up automatically.
        registry.addInterceptor(userContextMdcInterceptor);
    }
}
