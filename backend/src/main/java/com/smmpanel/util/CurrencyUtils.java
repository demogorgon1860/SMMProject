package com.smmpanel.util;

import com.smmpanel.exception.ExchangeRateException;
import com.smmpanel.service.CurrencyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/** Utility class for currency-related operations */
@UtilityClass
public class CurrencyUtils {

    // Default scale for decimal places in calculations
    private static final int DEFAULT_CALCULATION_SCALE = 10;

    // Default rounding mode for financial calculations
    private static final RoundingMode DEFAULT_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Validates that a currency code is supported by the system
     *
     * @param currencyCode The currency code to validate
     * @param currencyService The currency service to check against
     * @throws ExchangeRateException if the currency is not supported
     */
    public static void validateCurrencyCode(String currencyCode, CurrencyService currencyService) {
        if (currencyCode == null || currencyCode.trim().isEmpty()) {
            throw new ExchangeRateException("Currency code cannot be null or empty");
        }

        if (!currencyService.getSupportedCurrencies().containsKey(currencyCode.toUpperCase())) {
            throw new ExchangeRateException("Unsupported currency: " + currencyCode);
        }
    }

    /**
     * Formats a monetary amount according to the specified currency's formatting rules
     *
     * @param amount The amount to format
     * @param currencyCode The currency code (e.g., USD, EUR)
     * @param currencyService The currency service for getting currency information
     * @return Formatted currency string (e.g., "$1,234.56")
     */
    public static String formatCurrency(
            BigDecimal amount, String currencyCode, CurrencyService currencyService) {
        if (amount == null) {
            return "";
        }

        validateCurrencyCode(currencyCode, currencyService);

        // Get the currency symbol and decimal places
        String symbol = currencyService.getCurrencySymbol(currencyCode);
        int decimalPlaces = currencyService.getDecimalPlaces(currencyCode);

        // Create a number formatter with the correct locale and decimal places
        NumberFormat formatter = NumberFormat.getNumberInstance(Locale.US);
        formatter.setMinimumFractionDigits(decimalPlaces);
        formatter.setMaximumFractionDigits(decimalPlaces);
        formatter.setGroupingUsed(true);

        // Format the number and add the currency symbol
        String formattedNumber = formatter.format(amount);

        // Some currencies put the symbol after the amount
        if (currencyService.isSymbolAfterAmount(currencyCode)) {
            return formattedNumber + " " + symbol;
        }

        return symbol + formattedNumber;
    }

    /**
     * Converts an amount from one currency to another using the provided exchange rate
     *
     * @param amount The amount to convert
     * @param exchangeRate The exchange rate (1 unit of fromCurrency = exchangeRate units of
     *     toCurrency)
     * @param toCurrency The target currency code (for proper rounding)
     * @param currencyService The currency service for getting decimal places
     * @return The converted amount, properly rounded
     */
    public static BigDecimal convertAmount(
            BigDecimal amount,
            BigDecimal exchangeRate,
            String toCurrency,
            CurrencyService currencyService) {

        if (amount == null || exchangeRate == null) {
            throw new IllegalArgumentException("Amount and exchange rate cannot be null");
        }

        validateCurrencyCode(toCurrency, currencyService);

        int decimalPlaces = currencyService.getDecimalPlaces(toCurrency);

        return amount.multiply(exchangeRate).setScale(decimalPlaces, DEFAULT_ROUNDING);
    }

    /**
     * Safely parses a string into a BigDecimal for monetary values
     *
     * @param value The string value to parse
     * @return The parsed BigDecimal, or null if the input is null/empty
     * @throws NumberFormatException if the string cannot be parsed to a number
     */
    public static BigDecimal parseMonetaryValue(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Input monetary string is null or empty");
        }

        String s = raw.trim();

        // Keep leading minus
        boolean negative = s.startsWith("-");
        if (negative) s = s.substring(1).trim();

        // Remove currency symbols, spaces and letters, keep digits, dot and comma and minus if any
        s = s.replaceAll("[^0-9,\\.]", "");

        if (s.isEmpty()) {
            throw new IllegalArgumentException("No numeric content after cleaning: " + raw);
        }

        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');

        if (lastDot == -1 && lastComma == -1) {
            // pure integer digits
            String digits = s.replaceAll("[^0-9]", "");
            BigDecimal bd = new BigDecimal(digits);
            return negative ? bd.negate() : bd;
        }

        // Determine which separator is the decimal separator:
        int lastSepIndex = Math.max(lastDot, lastComma);
        char decimalSep = lastSepIndex == lastDot ? '.' : ',';

        // Mark the last occurrence of the decimal separator with placeholder 'D'
        StringBuilder sb = new StringBuilder(s);
        sb.setCharAt(lastSepIndex, 'D');

        // Remove all other separators (both '.' and ',') which are grouping separators
        String cleaned = sb.toString().replaceAll("[.,]", "");

        // Replace placeholder with '.' to create a standard decimal point
        cleaned = cleaned.replace('D', '.');

        // Prepend minus if negative
        if (negative) cleaned = "-" + cleaned;

        // Use BigDecimal to preserve precision
        return new BigDecimal(cleaned);
    }

    /**
     * Rounds a monetary value according to the specified currency's decimal places
     *
     * @param amount The amount to round
     * @param currencyCode The currency code
     * @param currencyService The currency service
     * @return The rounded amount
     */
    public static BigDecimal roundMonetaryValue(
            BigDecimal amount, String currencyCode, CurrencyService currencyService) {

        if (amount == null) {
            return null;
        }

        validateCurrencyCode(currencyCode, currencyService);

        int decimalPlaces = currencyService.getDecimalPlaces(currencyCode);
        return amount.setScale(decimalPlaces, DEFAULT_ROUNDING);
    }

    /**
     * Validates that an amount is positive
     *
     * @param amount The amount to validate
     * @param fieldName The name of the field for error messages
     * @throws IllegalArgumentException if the amount is null or not positive
     */
    public static void validatePositiveAmount(BigDecimal amount, String fieldName) {
        if (amount == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
    }
}
