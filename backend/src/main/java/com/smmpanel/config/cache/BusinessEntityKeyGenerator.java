package com.smmpanel.config.cache;

import java.lang.reflect.Method;
import java.util.StringJoiner;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

/**
 * Custom key generator for business entity caching Generates consistent cache keys for different
 * business entities
 */
@Component
public class BusinessEntityKeyGenerator implements KeyGenerator {

    private static final String SEPARATOR = ":";
    private static final String NULL_VALUE = "null";

    @Override
    public Object generate(Object target, Method method, Object... params) {
        StringJoiner keyBuilder = new StringJoiner(SEPARATOR);

        // Add class name
        keyBuilder.add(target.getClass().getSimpleName());

        // Add method name
        keyBuilder.add(method.getName());

        // Add parameters
        for (Object param : params) {
            if (param == null) {
                keyBuilder.add(NULL_VALUE);
            } else if (param instanceof Number || param instanceof String) {
                keyBuilder.add(param.toString());
            } else {
                keyBuilder.add(param.getClass().getSimpleName() + "@" + param.hashCode());
            }
        }

        return keyBuilder.toString();
    }

    /** Generate user balance cache key */
    public static String userBalanceKey(Long userId) {
        return "UserService:balance:" + userId;
    }

    /** Generate service definition cache key */
    public static String serviceDefinitionKey() {
        return "ServiceService:definitions:all";
    }

    /** Generate service definition cache key by category */
    public static String serviceDefinitionKey(String category) {
        return "ServiceService:definitions:" + (category != null ? category : "all");
    }

    /** Generate YouTube account availability cache key */
    public static String youtubeAccountKey(String accountId) {
        return "YouTubeService:account:" + accountId + ":availability";
    }

    /** Generate conversion coefficient cache key */
    public static String conversionCoefficientKey(String fromCurrency, String toCurrency) {
        return "ConversionService:coefficient:" + fromCurrency + ":" + toCurrency;
    }

    /** Generate exchange rate cache key */
    public static String exchangeRateKey(String fromCurrency, String toCurrency) {
        return "ExchangeRateService:rate:" + fromCurrency + ":" + toCurrency;
    }
}
