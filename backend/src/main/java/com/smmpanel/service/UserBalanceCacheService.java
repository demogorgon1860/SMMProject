package com.smmpanel.service;

import com.smmpanel.config.cache.BusinessEntityKeyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * User Balance Cache Service
 * Implements write-through caching strategy for user balances
 * 5-minute TTL as per optimization requirements
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBalanceCacheService {

    /**
     * Get cached user balance with 5-minute TTL
     * Cache miss will return null and should be populated by database lookup
     */
    @Cacheable(value = "user-balances", 
               key = "T(com.smmpanel.config.cache.BusinessEntityKeyGenerator).userBalanceKey(#userId)",
               unless = "#result == null")
    public BigDecimal getUserBalance(Long userId) {
        log.debug("Cache miss for user balance: {}", userId);
        return null; // This will be populated by the calling service from database
    }

    /**
     * Update user balance in cache (write-through)
     * This method should be called whenever balance is updated in database
     */
    @CachePut(value = "user-balances", 
              key = "T(com.smmpanel.config.cache.BusinessEntityKeyGenerator).userBalanceKey(#userId)")
    @Transactional
    public BigDecimal updateUserBalance(Long userId, BigDecimal newBalance) {
        log.debug("Updating cached balance for user {}: {}", userId, newBalance);
        return newBalance;
    }

    /**
     * Evict user balance from cache
     * Should be called when balance operation fails or for cache invalidation
     */
    @CacheEvict(value = "user-balances", 
                key = "T(com.smmpanel.config.cache.BusinessEntityKeyGenerator).userBalanceKey(#userId)")
    public void evictUserBalance(Long userId) {
        log.debug("Evicting balance cache for user: {}", userId);
    }

    /**
     * Check if user balance is cached
     */
    public boolean isBalanceCached(Long userId) {
        return getUserBalance(userId) != null;
    }

    /**
     * Preload user balance into cache
     * Used for warming cache after database operations
     */
    public void preloadBalance(Long userId, BigDecimal balance) {
        updateUserBalance(userId, balance);
        log.debug("Preloaded balance for user {}: {}", userId, balance);
    }

    /**
     * Evict multiple user balances (batch operation)
     */
    public void evictUserBalances(Long... userIds) {
        for (Long userId : userIds) {
            evictUserBalance(userId);
        }
        log.debug("Evicted balance cache for {} users", userIds.length);
    }
}