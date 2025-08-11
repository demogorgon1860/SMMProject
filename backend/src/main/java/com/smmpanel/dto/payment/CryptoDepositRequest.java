package com.smmpanel.dto.payment;

import com.smmpanel.security.validation.BalanceAmount;
import com.smmpanel.security.validation.CryptoAddress;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Enhanced Crypto Deposit Request DTO with comprehensive validation
 * 
 * VALIDATION FEATURES:
 * - Balance amount validation with fraud detection
 * - Cryptocurrency address validation with checksum verification
 * - Currency-specific limits and precision
 * - Malicious address detection
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoDepositRequest {
    
    @NotNull(message = "Amount is required")
    @BalanceAmount(
        operation = BalanceAmount.OperationType.DEPOSIT,
        currencyField = "currency",
        validateDailyLimits = true,
        enableFraudDetection = true,
        validatePrecision = true,
        message = "Invalid deposit amount"
    )
    private BigDecimal amount;
    
    @NotBlank(message = "Currency is required")
    @Pattern(
        regexp = "^(BTC|ETH|USDT|LTC|USDC)$", 
        message = "Supported currencies: BTC, ETH, USDT, LTC, USDC"
    )
    private String currency;
    
    @NotBlank(message = "Deposit address is required")
    @CryptoAddress(
        supportedCurrencies = {
            CryptoAddress.CurrencyType.BTC,
            CryptoAddress.CurrencyType.ETH, 
            CryptoAddress.CurrencyType.LTC,
            CryptoAddress.CurrencyType.USDT,
            CryptoAddress.CurrencyType.USDC
        },
        currencyField = "currency",
        validateChecksum = true,
        blockTestnet = true,
        validateMaliciousAddresses = true,
        allowLegacyFormats = true,
        message = "Invalid cryptocurrency address"
    )
    private String depositAddress;
    
    @Size(max = 100, message = "Transaction ID cannot exceed 100 characters")
    @Pattern(
        regexp = "^[a-fA-F0-9]*$",
        message = "Transaction ID must contain only hexadecimal characters"
    )
    private String transactionId;
    
    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    @Pattern(
        regexp = "^[\\p{L}\\p{N}\\s.,!?\\-]*$",
        message = "Notes contain invalid characters"
    )
    private String notes;
    
    @Builder.Default
    private Boolean notifyOnConfirmation = true;
    
    @Email(message = "Invalid notification email format")
    @Size(max = 255, message = "Email cannot exceed 255 characters")
    private String notificationEmail;
}