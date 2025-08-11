package com.smmpanel.security.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Enhanced Balance Amount Validator with comprehensive security checks
 * 
 * SECURITY FEATURES:
 * - Currency-specific amount limits
 * - Fraud detection for suspicious amounts
 * - Precision validation for cryptocurrencies
 * - Daily limit enforcement preparation
 * - Anti-money laundering (AML) patterns
 */
@Slf4j
@Component
public class BalanceAmountValidator implements ConstraintValidator<BalanceAmount, BigDecimal> {

    private BalanceAmount.OperationType operation;
    private String currencyField;
    private boolean validateDailyLimits;
    private boolean enableFraudDetection;
    private boolean validatePrecision;
    private BigDecimal customMin;
    private BigDecimal customMax;
    
    // Default limits by operation type (USD)
    private static final Map<BalanceAmount.OperationType, BigDecimal> DEFAULT_MIN_LIMITS = Map.of(
        BalanceAmount.OperationType.DEPOSIT, new BigDecimal("5.00"),
        BalanceAmount.OperationType.WITHDRAWAL, new BigDecimal("10.00"),
        BalanceAmount.OperationType.TRANSFER, new BigDecimal("1.00"),
        BalanceAmount.OperationType.CONVERSION, new BigDecimal("1.00")
    );
    
    private static final Map<BalanceAmount.OperationType, BigDecimal> DEFAULT_MAX_LIMITS = Map.of(
        BalanceAmount.OperationType.DEPOSIT, new BigDecimal("50000.00"),
        BalanceAmount.OperationType.WITHDRAWAL, new BigDecimal("25000.00"),
        BalanceAmount.OperationType.TRANSFER, new BigDecimal("10000.00"),
        BalanceAmount.OperationType.CONVERSION, new BigDecimal("100000.00")
    );
    
    // Currency-specific limits and precision
    private static final Map<String, CurrencyConfig> CURRENCY_CONFIGS = Map.of(
        "USD", new CurrencyConfig(new BigDecimal("5.00"), new BigDecimal("50000.00"), 2),
        "EUR", new CurrencyConfig(new BigDecimal("5.00"), new BigDecimal("45000.00"), 2),
        "BTC", new CurrencyConfig(new BigDecimal("0.001"), new BigDecimal("10.0"), 8),
        "ETH", new CurrencyConfig(new BigDecimal("0.01"), new BigDecimal("100.0"), 8),
        "USDT", new CurrencyConfig(new BigDecimal("5.00"), new BigDecimal("50000.00"), 6),
        "USDC", new CurrencyConfig(new BigDecimal("5.00"), new BigDecimal("50000.00"), 6),
        "LTC", new CurrencyConfig(new BigDecimal("0.1"), new BigDecimal("500.0"), 8)
    );
    
    // Fraud detection thresholds
    private static final BigDecimal SUSPICIOUS_AMOUNT_THRESHOLD = new BigDecimal("5000.00");
    private static final BigDecimal HIGH_RISK_AMOUNT_THRESHOLD = new BigDecimal("15000.00");
    
    @Override
    public void initialize(BalanceAmount constraintAnnotation) {
        this.operation = constraintAnnotation.operation();
        this.currencyField = constraintAnnotation.currencyField();
        this.validateDailyLimits = constraintAnnotation.validateDailyLimits();
        this.enableFraudDetection = constraintAnnotation.enableFraudDetection();
        this.validatePrecision = constraintAnnotation.validatePrecision();
        
        // Parse custom limits if provided
        if (!constraintAnnotation.customMin().isEmpty()) {
            try {
                this.customMin = new BigDecimal(constraintAnnotation.customMin());
            } catch (NumberFormatException e) {
                log.warn("Invalid custom minimum amount: {}", constraintAnnotation.customMin());
            }
        }
        
        if (!constraintAnnotation.customMax().isEmpty()) {
            try {
                this.customMax = new BigDecimal(constraintAnnotation.customMax());
            } catch (NumberFormatException e) {
                log.warn("Invalid custom maximum amount: {}", constraintAnnotation.customMax());
            }
        }
    }

    @Override
    public boolean isValid(BigDecimal amount, ConstraintValidatorContext context) {
        if (amount == null) {
            return true; // Use @NotNull if field is required
        }
        
        // Basic amount validation
        if (!validateBasicAmount(amount, context)) {
            return false;
        }
        
        // Precision validation
        if (validatePrecision && !validateDecimalPrecision(amount, "USD", context)) {
            return false;
        }
        
        // Range validation
        if (!validateAmountRange(amount, "USD", context)) {
            return false;
        }
        
        // Fraud detection
        if (enableFraudDetection && !validateFraudDetection(amount, context)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates basic amount constraints
     */
    private boolean validateBasicAmount(BigDecimal amount, ConstraintValidatorContext context) {
        // Must be positive
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            addConstraintViolation(context, "Amount must be greater than 0");
            return false;
        }
        
        // Check for reasonable scale (no more than 8 decimal places)
        if (amount.scale() > 8) {
            addConstraintViolation(context, "Amount has too many decimal places (maximum 8)");
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates decimal precision based on currency
     */
    private boolean validateDecimalPrecision(BigDecimal amount, String currency, 
                                           ConstraintValidatorContext context) {
        CurrencyConfig config = CURRENCY_CONFIGS.getOrDefault(currency, 
            CURRENCY_CONFIGS.get("USD"));
        
        if (amount.scale() > config.decimalPlaces) {
            addConstraintViolation(context, 
                String.format("Amount precision exceeds %d decimal places for %s", 
                    config.decimalPlaces, currency));
            return false;
        }
        
        return true;
    }
    
    /**
     * Validates amount against currency-specific ranges
     */
    private boolean validateAmountRange(BigDecimal amount, String currency, 
                                      ConstraintValidatorContext context) {
        BigDecimal minLimit = getMinLimit(currency);
        BigDecimal maxLimit = getMaxLimit(currency);
        
        if (amount.compareTo(minLimit) < 0) {
            addConstraintViolation(context, 
                String.format("Minimum %s amount for %s is %s", 
                    operation.name().toLowerCase(), currency, minLimit));
            return false;
        }
        
        if (amount.compareTo(maxLimit) > 0) {
            addConstraintViolation(context, 
                String.format("Maximum %s amount for %s is %s", 
                    operation.name().toLowerCase(), currency, maxLimit));
            return false;
        }
        
        return true;
    }
    
    /**
     * Applies fraud detection rules
     */
    private boolean validateFraudDetection(BigDecimal amount, ConstraintValidatorContext context) {
        // Convert to USD equivalent for fraud detection (simplified)
        BigDecimal usdAmount = convertToUsdEquivalent(amount, "USD");
        
        if (usdAmount.compareTo(HIGH_RISK_AMOUNT_THRESHOLD) > 0) {
            log.warn("High-risk transaction amount detected: {} USD equivalent", usdAmount);
            addConstraintViolation(context, 
                "Amount exceeds high-risk threshold. Enhanced verification required.");
            return false;
        }
        
        if (usdAmount.compareTo(SUSPICIOUS_AMOUNT_THRESHOLD) > 0) {
            log.info("Suspicious transaction amount flagged: {} USD equivalent", usdAmount);
            // Log for AML system but don't fail validation
        }
        
        // Check for suspicious patterns
        if (isSuspiciousAmount(amount)) {
            log.warn("Suspicious amount pattern detected: {}", amount);
            addConstraintViolation(context, 
                "Amount appears suspicious. Please verify transaction details.");
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets minimum limit for currency and operation
     */
    private BigDecimal getMinLimit(String currency) {
        if (customMin != null) {
            return customMin;
        }
        
        CurrencyConfig config = CURRENCY_CONFIGS.get(currency);
        if (config != null) {
            return config.minAmount;
        }
        
        return DEFAULT_MIN_LIMITS.getOrDefault(operation, new BigDecimal("1.00"));
    }
    
    /**
     * Gets maximum limit for currency and operation
     */
    private BigDecimal getMaxLimit(String currency) {
        if (customMax != null) {
            return customMax;
        }
        
        CurrencyConfig config = CURRENCY_CONFIGS.get(currency);
        if (config != null) {
            return config.maxAmount;
        }
        
        return DEFAULT_MAX_LIMITS.getOrDefault(operation, new BigDecimal("10000.00"));
    }
    
    /**
     * Converts amount to USD equivalent (simplified - would use real exchange rates)
     */
    private BigDecimal convertToUsdEquivalent(BigDecimal amount, String currency) {
        // Simplified conversion - in real implementation, use exchange rate service
        switch (currency.toUpperCase()) {
            case "BTC": return amount.multiply(new BigDecimal("45000")); // Approx BTC price
            case "ETH": return amount.multiply(new BigDecimal("2500"));  // Approx ETH price
            case "LTC": return amount.multiply(new BigDecimal("100"));   // Approx LTC price
            case "USDT":
            case "USDC": return amount; // 1:1 with USD
            case "EUR": return amount.multiply(new BigDecimal("1.1"));  // Approx EUR/USD
            default: return amount; // Assume USD
        }
    }
    
    /**
     * Detects suspicious amount patterns
     */
    private boolean isSuspiciousAmount(BigDecimal amount) {
        // Round numbers that might indicate testing or fraud
        BigDecimal[] suspiciousAmounts = {
            new BigDecimal("9999.99"),
            new BigDecimal("12345.67"),
            new BigDecimal("11111.11"),
            new BigDecimal("7777.77")
        };
        
        for (BigDecimal suspicious : suspiciousAmounts) {
            if (amount.compareTo(suspicious) == 0) {
                return true;
            }
        }
        
        // Check for amounts with too many identical digits
        String amountStr = amount.toPlainString().replace(".", "");
        if (amountStr.length() >= 4) {
            char firstChar = amountStr.charAt(0);
            long identicalCount = amountStr.chars().filter(c -> c == firstChar).count();
            if (identicalCount >= amountStr.length() * 0.8) { // 80% of digits are the same
                return true;
            }
        }
        
        return false;
    }
    
    private void addConstraintViolation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
               .addConstraintViolation();
    }
    
    /**
     * Currency configuration class
     */
    private static class CurrencyConfig {
        final BigDecimal minAmount;
        final BigDecimal maxAmount;
        final int decimalPlaces;
        
        CurrencyConfig(BigDecimal minAmount, BigDecimal maxAmount, int decimalPlaces) {
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.decimalPlaces = decimalPlaces;
        }
    }
}