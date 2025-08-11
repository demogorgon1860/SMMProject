package com.smmpanel.security.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * Custom validation annotation for cryptocurrency addresses
 * 
 * VALIDATION FEATURES:
 * - Multi-currency address format validation
 * - Checksum verification for supported currencies
 * - Malicious address detection
 * - Address type validation (P2PKH, P2SH, Bech32, etc.)
 * - Testnet address detection and blocking
 */
@Documented
@Constraint(validatedBy = CryptoAddressValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CryptoAddress {
    String message() default "Invalid cryptocurrency address";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    /**
     * Supported cryptocurrency types
     */
    CurrencyType[] supportedCurrencies() default {
        CurrencyType.BTC, CurrencyType.ETH, CurrencyType.LTC, 
        CurrencyType.USDT, CurrencyType.USDC
    };
    
    /**
     * Currency field name for currency-specific validation
     */
    String currencyField() default "currency";
    
    /**
     * Whether to validate address checksums
     */
    boolean validateChecksum() default true;
    
    /**
     * Whether to block testnet addresses
     */
    boolean blockTestnet() default true;
    
    /**
     * Whether to validate against known malicious addresses
     */
    boolean validateMaliciousAddresses() default true;
    
    /**
     * Whether to allow legacy address formats
     */
    boolean allowLegacyFormats() default true;
    
    public enum CurrencyType {
        BTC("Bitcoin"),
        ETH("Ethereum"), 
        LTC("Litecoin"),
        USDT("Tether"),
        USDC("USD Coin"),
        BCH("Bitcoin Cash"),
        XRP("Ripple"),
        ADA("Cardano"),
        DOT("Polkadot");
        
        private final String displayName;
        
        CurrencyType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
}