package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.balance.CurrencyConversionResultDto;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ExchangeRateException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service for managing user currency preferences and operations */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCurrencyPreferenceService {

    private final UserRepository userRepository;
    private final CurrencyConversionService currencyConversionService;
    private final CurrencyService currencyService;

    /** Get the user's preferred currency */
    @Cacheable(value = "userCurrency", key = "#userId")
    public String getUserPreferredCurrency(Long userId) {
        return userRepository
                .findById(userId)
                .map(User::getPreferredCurrency)
                .orElseGet(currencyService::getDefaultCurrency);
    }

    /** Update the user's preferred currency */
    @Transactional
    @CacheEvict(value = "userCurrency", key = "#userId")
    public void updateUserPreferredCurrency(Long userId, String currencyCode) {
        // Validate the currency code
        if (!currencyService.getSupportedCurrencies().containsKey(currencyCode)) {
            throw new ExchangeRateException("Unsupported currency: " + currencyCode);
        }

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "User not found with id: " + userId));

        user.setPreferredCurrency(currencyCode);
        userRepository.save(user);

        log.info("Updated preferred currency for user {} to {}", userId, currencyCode);
    }

    /** Convert an amount from the user's preferred currency to another currency */
    public CurrencyConversionResultDto convertFromUserCurrency(
            Long userId, BigDecimal amount, String targetCurrency, boolean format) {

        String sourceCurrency = getUserPreferredCurrency(userId);
        return currencyConversionService.convert(
                new CurrencyConversionRequest(amount, sourceCurrency, targetCurrency, format));
    }

    /** Convert an amount to the user's preferred currency */
    public CurrencyConversionResultDto convertToUserCurrency(
            Long userId, BigDecimal amount, String sourceCurrency, boolean format) {

        String targetCurrency = getUserPreferredCurrency(userId);
        return currencyConversionService.convert(
                new CurrencyConversionRequest(amount, sourceCurrency, targetCurrency, format));
    }

    /** Format an amount in the user's preferred currency */
    public String formatForUser(Long userId, BigDecimal amount) {
        String currencyCode = getUserPreferredCurrency(userId);
        return currencyService.formatCurrency(amount, currencyCode);
    }

    /** Get exchange rate from user's preferred currency to target currency */
    public BigDecimal getExchangeRateFromUserCurrency(Long userId, String targetCurrency) {
        String sourceCurrency = getUserPreferredCurrency(userId);
        return currencyConversionService.getExchangeRate(sourceCurrency, targetCurrency);
    }

    /** Get exchange rate to user's preferred currency from source currency */
    public BigDecimal getExchangeRateToUserCurrency(Long userId, String sourceCurrency) {
        String targetCurrency = getUserPreferredCurrency(userId);
        return currencyConversionService.getExchangeRate(sourceCurrency, targetCurrency);
    }

    /** Get a list of all supported currencies with user's preferred currency marked */
    public List<CurrencyService.CurrencyInfo> getSupportedCurrenciesWithUserPreference(
            Long userId) {
        String userCurrency = getUserPreferredCurrency(userId);
        return currencyService.getSupportedCurrencies().entrySet().stream()
                .map(
                        entry -> {
                            CurrencyService.CurrencyInfo info = entry.getValue();
                            info.setPreferred(entry.getKey().equals(userCurrency));
                            return info;
                        })
                .toList();
    }

    /** Clear the user's currency preference cache */
    @CacheEvict(value = "userCurrency", key = "#userId")
    public void clearUserCurrencyCache(Long userId) {
        log.debug("Cleared currency preference cache for user {}", userId);
    }
}
