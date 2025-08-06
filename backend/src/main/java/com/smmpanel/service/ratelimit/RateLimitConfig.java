package com.smmpanel.service.ratelimit;

import lombok.Getter;
import java.util.HashMap;
import java.util.Map;

@Getter
public class RateLimitConfig {
    private final String endpoint;
    private final int baseLimit;
    private final int windowSeconds;
    
    private static final Map<String, RateLimitConfig> ENDPOINT_CONFIGS = new HashMap<>();
    
    static {
        // API endpoints
        ENDPOINT_CONFIGS.put("/api/orders/**", new RateLimitConfig("/api/orders/**", 100, 60));
        ENDPOINT_CONFIGS.put("/api/services/**", new RateLimitConfig("/api/services/**", 300, 60));
        ENDPOINT_CONFIGS.put("/api/balance/**", new RateLimitConfig("/api/balance/**", 50, 60));
        
        // User management
        ENDPOINT_CONFIGS.put("/api/user/**", new RateLimitConfig("/api/user/**", 30, 60));
        ENDPOINT_CONFIGS.put("/api/auth/**", new RateLimitConfig("/api/auth/**", 10, 60));
        
        // Video processing
        ENDPOINT_CONFIGS.put("/api/video/**", new RateLimitConfig("/api/video/**", 50, 60));
        
        // Default config for unspecified endpoints
        ENDPOINT_CONFIGS.put("default", new RateLimitConfig("default", 200, 60));
    }

    private RateLimitConfig(String endpoint, int baseLimit, int windowSeconds) {
        this.endpoint = endpoint;
        this.baseLimit = baseLimit;
        this.windowSeconds = windowSeconds;
    }

    public static RateLimitConfig getForEndpoint(String endpoint) {
        return ENDPOINT_CONFIGS.entrySet().stream()
                .filter(entry -> endpoint.matches(entry.getKey().replace("**", ".*")))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(ENDPOINT_CONFIGS.get("default"));
    }
}
