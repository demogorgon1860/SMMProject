package com.smmpanel.config;

import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Configuration
@RequiredArgsConstructor
public class JwtConfig {

    private final AppProperties appProperties;

    @Bean
    public SecretKey jwtSecretKey() {
        String jwtSecret = appProperties.getJwt().getSecret();
        if (jwtSecret == null || jwtSecret.length() < 64) {
            throw new IllegalStateException(
                "JWT secret must be at least 64 characters long. " +
                "Please set app.jwt.secret in your application properties");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
}
