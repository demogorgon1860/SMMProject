package com.smmpanel.config;

import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.jwt")
@Data
public class JwtConfig {

    @Value("${JWT_SECRET}")
    private String secret;

    @Value("${JWT_EXPIRATION_MS:86400000}")
    private long jwtExpirationMs;

    @Value("${JWT_REFRESH_EXPIRATION_MS:604800000}")
    private long refreshExpirationMs;

    private String jwtIssuer = "SMM-Panel";

    @Bean
    public SecretKey jwtSecretKey() {
        if (secret == null || secret.length() < 64) {
            throw new IllegalStateException(
                    "JWT secret must be at least 64 characters long. "
                            + "Please set jwt.secret in your application properties");
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }
}
