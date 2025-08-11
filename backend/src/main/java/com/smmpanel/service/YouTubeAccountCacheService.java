package com.smmpanel.service;

import com.smmpanel.config.cache.BusinessEntityKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * YouTube Account Availability Cache Service
 * 30-second TTL for real-time account availability status
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeAccountCacheService {

    /**
     * Get cached YouTube account availability with 30-second TTL
     * Returns true if account is available, false if not, null if not cached
     */
    @Cacheable(value = "youtube-accounts", 
               key = "T(com.smmpanel.config.cache.BusinessEntityKeyGenerator).youtubeAccountKey(#accountId)",
               unless = "#result == null")
    public Boolean getAccountAvailability(String accountId) {
        log.debug("Cache miss for YouTube account availability: {}", accountId);
        return null; // Will be populated by actual availability check
    }

    /**
     * Update YouTube account availability in cache
     */
    @CachePut(value = "youtube-accounts", 
              key = "T(com.smmpanel.config.cache.BusinessEntityKeyGenerator).youtubeAccountKey(#accountId)")
    public Boolean updateAccountAvailability(String accountId, Boolean isAvailable) {
        log.debug("Updating cached availability for YouTube account {}: {}", accountId, isAvailable);
        return isAvailable;
    }

    /**
     * Evict YouTube account availability from cache
     */
    @CacheEvict(value = "youtube-accounts", 
                key = "T(com.smmpanel.config.cache.BusinessEntityKeyGenerator).youtubeAccountKey(#accountId)")
    public void evictAccountAvailability(String accountId) {
        log.debug("Evicting availability cache for YouTube account: {}", accountId);
    }

    /**
     * Check if account availability is cached
     */
    public boolean isAccountAvailabilityCached(String accountId) {
        return getAccountAvailability(accountId) != null;
    }

    /**
     * Mark account as available (cache update)
     */
    public void markAccountAsAvailable(String accountId) {
        updateAccountAvailability(accountId, true);
    }

    /**
     * Mark account as unavailable (cache update)
     */
    public void markAccountAsUnavailable(String accountId) {
        updateAccountAvailability(accountId, false);
    }

    /**
     * Evict multiple account availabilities (batch operation)
     */
    public void evictAccountAvailabilities(String... accountIds) {
        for (String accountId : accountIds) {
            evictAccountAvailability(accountId);
        }
        log.debug("Evicted availability cache for {} YouTube accounts", accountIds.length);
    }

    /**
     * Clear all YouTube account availability cache
     */
    @CacheEvict(value = "youtube-accounts", allEntries = true)
    public void clearAllAccountAvailabilities() {
        log.debug("Cleared all YouTube account availability cache");
    }
}