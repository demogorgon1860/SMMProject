package com.smmpanel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Redis Cache Warmup Service Pre-loads frequently accessed data into cache on application startup
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Profile({"prod", "stage"})
public class RedisCacheWarmupService {

    private final CacheManager cacheManager;
    private final ServiceService serviceService;
    private final ExchangeRateService exchangeRateService;

    /** Warm up caches after application startup */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmupCaches() {
        log.info("Starting Redis cache warmup...");

        try {
            // Warm up services cache
            warmupServicesCache();

            // Warm up exchange rates cache
            warmupExchangeRatesCache();

            log.info("Redis cache warmup completed successfully");

        } catch (Exception e) {
            log.error("Error during cache warmup: {}", e.getMessage(), e);
        }
    }

    /** Pre-load services into cache */
    private void warmupServicesCache() {
        try {
            log.debug("Warming up services cache...");

            // This will trigger @Cacheable and load data into cache
            serviceService.getAllActiveServices();

            log.debug("Services cache warmed up successfully");
        } catch (Exception e) {
            log.error("Failed to warm up services cache: {}", e.getMessage());
        }
    }

    /** Pre-load exchange rates into cache */
    private void warmupExchangeRatesCache() {
        try {
            log.debug("Warming up exchange rates cache...");

            // Load common exchange rates
            String[] currencies = {"USD", "EUR", "GBP", "JPY", "CNY", "RUB"};

            for (String from : currencies) {
                for (String to : currencies) {
                    if (!from.equals(to)) {
                        try {
                            exchangeRateService.getExchangeRate(from, to);
                        } catch (Exception e) {
                            log.debug(
                                    "Could not load exchange rate for {}/{}: {}",
                                    from,
                                    to,
                                    e.getMessage());
                        }
                    }
                }
            }

            log.debug("Exchange rates cache warmed up successfully");
        } catch (Exception e) {
            log.error("Failed to warm up exchange rates cache: {}", e.getMessage());
        }
    }

    /** Clear all caches */
    public void clearAllCaches() {
        cacheManager
                .getCacheNames()
                .forEach(
                        cacheName -> {
                            var cache = cacheManager.getCache(cacheName);
                            if (cache != null) {
                                cache.clear();
                                log.info("Cleared cache: {}", cacheName);
                            }
                        });
    }

    /** Get cache statistics */
    public void logCacheStatistics() {
        cacheManager
                .getCacheNames()
                .forEach(
                        cacheName -> {
                            log.info("Cache available: {}", cacheName);
                        });
    }
}
