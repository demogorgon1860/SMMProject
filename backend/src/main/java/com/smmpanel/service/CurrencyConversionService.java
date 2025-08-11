package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.balance.CurrencyConversionResultDto;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ExchangeRateException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service for handling currency conversion and formatting operations */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrencyConversionService {

    private final ExchangeRateService exchangeRateService;
    private final CurrencyService currencyService;

    /** Convert an amount from one currency to another */
    public CurrencyConversionResultDto convert(CurrencyConversionRequest request) {
        BigDecimal amount = request.getAmount();
        String fromCurrency = request.getFromCurrency().toUpperCase();
        String toCurrency = request.getToCurrency().toUpperCase();
        boolean format = request.isFormat();

        // Validate currencies
        validateCurrencyCode(fromCurrency);
        validateCurrencyCode(toCurrency);

        // Get the exchange rate
        BigDecimal rate;
        if (fromCurrency.equals(toCurrency)) {
            rate = BigDecimal.ONE;
        } else {
            rate = exchangeRateService.getExchangeRate(fromCurrency, toCurrency);
            if (rate == null) {
                throw new ExchangeRateException(
                        "Exchange rate not available for " + fromCurrency + " to " + toCurrency);
            }
        }

        // Perform the conversion
        BigDecimal convertedAmount =
                amount.multiply(rate)
                        .setScale(
                                currencyService.getDecimalPlaces(toCurrency), RoundingMode.HALF_UP);

        // Format the result if requested
        String formattedAmount = format ? formatCurrency(convertedAmount, toCurrency) : null;

        return CurrencyConversionResultDto.builder()
                .amount(amount)
                .fromCurrency(fromCurrency)
                .toCurrency(toCurrency)
                .exchangeRate(rate)
                .convertedAmount(convertedAmount)
                .formattedAmount(formattedAmount)
                .build();
    }

    /** Get the exchange rate between two currencies */
    @Cacheable(value = "exchangeRates", key = "#fromCurrency + '_' + #toCurrency")
    public BigDecimal getExchangeRate(String fromCurrency, String toCurrency) {
        validateCurrencyCode(fromCurrency);
        validateCurrencyCode(toCurrency);

        if (fromCurrency.equals(toCurrency)) {
            return BigDecimal.ONE;
        }

        return exchangeRateService.getExchangeRate(fromCurrency, toCurrency);
    }

    /** Format a monetary amount according to the specified currency */
    public String formatCurrency(BigDecimal amount, String currencyCode) {
        validateCurrencyCode(currencyCode);

        int decimalPlaces = currencyService.getDecimalPlaces(currencyCode);
        String symbol = currencyService.getCurrencySymbol(currencyCode);

        // Format the number with proper grouping and decimal places
        String formatPattern = "%,." + decimalPlaces + "f";
        String formattedNumber = String.format(Locale.US, formatPattern, amount);

        // Add currency symbol based on locale
        boolean symbolAfterAmount = currencyService.isSymbolAfterAmount(currencyCode);
        return symbolAfterAmount ? formattedNumber + " " + symbol : symbol + formattedNumber;
    }

    /** Format a monetary amount according to the user's preferred currency */
    public String formatForUser(BigDecimal amount, User user) {
        String currencyCode = user.getPreferredCurrency();
        return formatCurrency(amount, currencyCode);
    }

    /** Convert and format an amount to the user's preferred currency */
    public String convertAndFormatForUser(BigDecimal amount, String fromCurrency, User user) {
        String toCurrency = user.getPreferredCurrency();

        if (fromCurrency.equals(toCurrency)) {
            return formatCurrency(amount, toCurrency);
        }

        BigDecimal convertedAmount =
                convert(new CurrencyConversionRequest(amount, fromCurrency, toCurrency, false))
                        .getConvertedAmount();

        return formatCurrency(convertedAmount, toCurrency);
    }

    /** Validate that a currency code is supported */
    private void validateCurrencyCode(String currencyCode) {
        if (!currencyService.getSupportedCurrencies().containsKey(currencyCode)) {
            throw new ExchangeRateException("Unsupported currency: " + currencyCode);
        }
    }
}
