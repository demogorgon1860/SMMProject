package com.smmpanel.config;

import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Configuration
@Getter
public class JwtConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration:86400000}") // 24h default
    private long jwtExpirationMs;

    @Value("${app.jwt.issuer:smmpanel}")
    private String jwtIssuer;

    @Bean
    public SecretKey jwtSecretKey() {
        if (jwtSecret == null || jwtSecret.length() < 64) {
            throw new IllegalStateException(
                "JWT secret must be at least 64 characters long. " +
                "Please set app.jwt.secret in your application properties");
        }
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
}
