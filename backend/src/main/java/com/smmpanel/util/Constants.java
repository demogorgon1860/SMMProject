package com.smmpanel.util;

/**
 * Application constants
 */
public final class Constants {
    
    // API Configuration
    public static final String API_BASE_PATH = "/api/v2";
    public static final String API_VERSION = "v2";
    
    // Default values
    public static final String DEFAULT_CURRENCY = "USD";
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    
    // Cache constants
    public static final String CACHE_EXCHANGE_RATES = "exchange_rates";
    public static final String CACHE_SERVICES = "services";
    public static final String CACHE_USER_BALANCE = "user_balance";
    
    // Rate limiting
    public static final int DEFAULT_RATE_LIMIT = 100;
    public static final long DEFAULT_RATE_LIMIT_WINDOW_MS = 3600000; // 1 hour
    
    // Order processing
    public static final int MAX_RETRY_ATTEMPTS = 3;
    public static final long RETRY_DELAY_MS = 60000; // 1 minute
    
    private Constants() {
        // Utility class - no instantiation
    }
} 