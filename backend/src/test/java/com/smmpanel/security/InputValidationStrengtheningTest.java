package com.smmpanel.security;

import com.smmpanel.dto.OrderRequestDTO;
import com.smmpanel.dto.balance.CreateDepositRequest;
import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.payment.CryptoDepositRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive Input Validation Strengthening Tests
 * 
 * Tests the enhanced Jakarta Bean Validation custom validators for:
 * - YouTube URL format validation
 * - Order quantity limits (100-1,000,000)
 * - Balance operation amounts
 * - Cryptocurrency addresses
 */
@DisplayName("Input Validation Strengthening Tests")
public class InputValidationStrengtheningTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Nested
    @DisplayName("YouTube URL Validation Tests")
    class YouTubeUrlValidationTests {

        @Test
        @DisplayName("Valid YouTube URLs should pass validation")
        void testValidYouTubeUrls() {
            OrderRequestDTO request = new OrderRequestDTO();
            request.setServiceId(1L);
            request.setQuantity(1000);
            request.setAmount(new BigDecimal("10.00"));

            // Test valid URLs
            String[] validUrls = {
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ",
                "https://www.youtube.com/embed/dQw4w9WgXcQ",
                "https://www.youtube.com/v/dQw4w9WgXcQ"
            };

            for (String url : validUrls) {
                request.setUrl(url);
                Set<ConstraintViolation<OrderRequestDTO>> violations = validator.validate(request);
                
                boolean hasUrlViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("url"));
                
                assertFalse(hasUrlViolation, "Valid URL should not have violations: " + url);
            }
        }

        @Test
        @DisplayName("Invalid YouTube URLs should fail validation")
        void testInvalidYouTubeUrls() {
            OrderRequestDTO request = new OrderRequestDTO();
            request.setServiceId(1L);
            request.setQuantity(1000);
            request.setAmount(new BigDecimal("10.00"));

            // Test invalid URLs
            String[] invalidUrls = {
                "http://www.youtube.com/watch?v=dQw4w9WgXcQ", // HTTP not HTTPS
                "https://vimeo.com/123456789", // Not YouTube
                "https://www.youtube.com/watch?v=invalid", // Invalid video ID
                "javascript:alert('xss')", // Malicious content
                "https://www.youtube.com/watch", // No video ID
                "not_a_url_at_all"
            };

            for (String url : invalidUrls) {
                request.setUrl(url);
                Set<ConstraintViolation<OrderRequestDTO>> violations = validator.validate(request);
                
                boolean hasUrlViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("url"));
                
                assertTrue(hasUrlViolation, "Invalid URL should have violations: " + url);
            }
        }
    }

    @Nested
    @DisplayName("Order Quantity Validation Tests")
    class OrderQuantityValidationTests {

        @ParameterizedTest
        @ValueSource(ints = {100, 500, 1000, 50000, 500000, 1000000})
        @DisplayName("Valid quantities should pass validation")
        void testValidQuantities(int quantity) {
            OrderRequestDTO request = new OrderRequestDTO();
            request.setServiceId(1L);
            request.setQuantity(quantity);
            request.setUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            request.setAmount(new BigDecimal("10.00"));

            Set<ConstraintViolation<OrderRequestDTO>> violations = validator.validate(request);
            
            boolean hasQuantityViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("quantity"));
            
            assertFalse(hasQuantityViolation, "Valid quantity should not have violations: " + quantity);
        }

        @ParameterizedTest
        @ValueSource(ints = {50, 99, 1000001, 2000000})
        @DisplayName("Invalid quantities should fail validation")
        void testInvalidQuantities(int quantity) {
            OrderRequestDTO request = new OrderRequestDTO();
            request.setServiceId(1L);
            request.setQuantity(quantity);
            request.setUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            request.setAmount(new BigDecimal("10.00"));

            Set<ConstraintViolation<OrderRequestDTO>> violations = validator.validate(request);
            
            boolean hasQuantityViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("quantity"));
            
            assertTrue(hasQuantityViolation, "Invalid quantity should have violations: " + quantity);
        }

        @Test
        @DisplayName("Suspicious quantities should be detected")
        void testSuspiciousQuantities() {
            OrderRequestDTO request = new OrderRequestDTO();
            request.setServiceId(1L);
            request.setUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
            request.setAmount(new BigDecimal("10.00"));

            // Test suspicious patterns
            int[] suspiciousQuantities = {999999, 123456, 777777};

            for (int quantity : suspiciousQuantities) {
                request.setQuantity(quantity);
                Set<ConstraintViolation<OrderRequestDTO>> violations = validator.validate(request);
                
                boolean hasQuantityViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("quantity") && 
                               v.getMessage().contains("suspicious"));
                
                assertTrue(hasQuantityViolation, "Suspicious quantity should be detected: " + quantity);
            }
        }
    }

    @Nested
    @DisplayName("Balance Amount Validation Tests")
    class BalanceAmountValidationTests {

        @Test
        @DisplayName("Valid deposit amounts should pass validation")
        void testValidDepositAmounts() {
            String[] validAmounts = {"5.00", "10.50", "100.00", "1000.00", "9999.99"};

            for (String amountStr : validAmounts) {
                CreateDepositRequest request = CreateDepositRequest.builder()
                    .amount(new BigDecimal(amountStr))
                    .currency("USD")
                    .build();

                Set<ConstraintViolation<CreateDepositRequest>> violations = validator.validate(request);
                
                boolean hasAmountViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
                
                assertFalse(hasAmountViolation, "Valid amount should not have violations: " + amountStr);
            }
        }

        @Test
        @DisplayName("Invalid deposit amounts should fail validation")
        void testInvalidDepositAmounts() {
            String[] invalidAmounts = {"0", "-10.00", "100000.00", "0.001"};

            for (String amountStr : invalidAmounts) {
                CreateDepositRequest request = CreateDepositRequest.builder()
                    .amount(new BigDecimal(amountStr))
                    .currency("USD")
                    .build();

                Set<ConstraintViolation<CreateDepositRequest>> violations = validator.validate(request);
                
                boolean hasAmountViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
                
                assertTrue(hasAmountViolation, "Invalid amount should have violations: " + amountStr);
            }
        }

        @Test
        @DisplayName("Cryptocurrency precision should be validated")
        void testCryptoPrecisionValidation() {
            CurrencyConversionRequest request = CurrencyConversionRequest.builder()
                .amount(new BigDecimal("1.123456789")) // Too many decimal places
                .fromCurrency("BTC")
                .toCurrency("USD")
                .build();

            Set<ConstraintViolation<CurrencyConversionRequest>> violations = validator.validate(request);
            
            boolean hasAmountViolation = violations.stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
            
            assertTrue(hasAmountViolation, "Excessive precision should be detected");
        }
    }

    @Nested
    @DisplayName("Cryptocurrency Address Validation Tests")
    class CryptoAddressValidationTests {

        @Test
        @DisplayName("Valid Bitcoin addresses should pass validation")
        void testValidBitcoinAddresses() {
            String[] validAddresses = {
                "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", // Legacy P2PKH
                "3J98t1WpEZ73CNmQviecrnyiWrnqRhWNLy", // P2SH
                "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"  // Bech32
            };

            for (String address : validAddresses) {
                CryptoDepositRequest request = CryptoDepositRequest.builder()
                    .amount(new BigDecimal("0.001"))
                    .currency("BTC")
                    .depositAddress(address)
                    .build();

                Set<ConstraintViolation<CryptoDepositRequest>> violations = validator.validate(request);
                
                boolean hasAddressViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("depositAddress"));
                
                assertFalse(hasAddressViolation, "Valid Bitcoin address should not have violations: " + address);
            }
        }

        @Test
        @DisplayName("Valid Ethereum addresses should pass validation")
        void testValidEthereumAddresses() {
            String[] validAddresses = {
                "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", // EIP-55 checksum
                "0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed", // All lowercase
                "0X5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED"  // All uppercase
            };

            for (String address : validAddresses) {
                CryptoDepositRequest request = CryptoDepositRequest.builder()
                    .amount(new BigDecimal("0.01"))
                    .currency("ETH")
                    .depositAddress(address)
                    .build();

                Set<ConstraintViolation<CryptoDepositRequest>> violations = validator.validate(request);
                
                boolean hasAddressViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("depositAddress"));
                
                assertFalse(hasAddressViolation, "Valid Ethereum address should not have violations: " + address);
            }
        }

        @Test
        @DisplayName("Invalid cryptocurrency addresses should fail validation")
        void testInvalidCryptoAddresses() {
            String[] invalidAddresses = {
                "invalid_address",
                "1234567890", // Too short
                "0x1234567890abcdef", // Too short for Ethereum
                "bc1invalid", // Invalid Bech32
                "javascript:alert('xss')", // Malicious content
                ""
            };

            for (String address : invalidAddresses) {
                CryptoDepositRequest request = CryptoDepositRequest.builder()
                    .amount(new BigDecimal("0.001"))
                    .currency("BTC")
                    .depositAddress(address)
                    .build();

                Set<ConstraintViolation<CryptoDepositRequest>> violations = validator.validate(request);
                
                boolean hasAddressViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("depositAddress"));
                
                assertTrue(hasAddressViolation, "Invalid address should have violations: " + address);
            }
        }

        @Test
        @DisplayName("Testnet addresses should be blocked")
        void testTestnetAddressBlocking() {
            String[] testnetAddresses = {
                "2MzQwSSnBHWHqSAqtTVQ6v47XtaisrJa1Vc", // Bitcoin testnet
                "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", // Bitcoin testnet Bech32
                "n1LKejAadN6hg2FrBXoU1KrwX4uK16mco9" // Bitcoin testnet legacy
            };

            for (String address : testnetAddresses) {
                CryptoDepositRequest request = CryptoDepositRequest.builder()
                    .amount(new BigDecimal("0.001"))
                    .currency("BTC")
                    .depositAddress(address)
                    .build();

                Set<ConstraintViolation<CryptoDepositRequest>> violations = validator.validate(request);
                
                boolean hasTestnetViolation = violations.stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("depositAddress") &&
                               v.getMessage().contains("testnet"));
                
                assertTrue(hasTestnetViolation, "Testnet address should be blocked: " + address);
            }
        }
    }

    @Test
    @DisplayName("Currency consistency validation")
    void testCurrencyConsistency() {
        // Test inconsistent currency and address
        CryptoDepositRequest request = CryptoDepositRequest.builder()
            .amount(new BigDecimal("0.001"))
            .currency("ETH") // Ethereum currency
            .depositAddress("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2") // Bitcoin address
            .build();

        Set<ConstraintViolation<CryptoDepositRequest>> violations = validator.validate(request);
        
        // Should detect currency-address mismatch
        boolean hasCurrencyMismatch = violations.stream()
            .anyMatch(v -> v.getMessage().contains("currency") || v.getMessage().contains("format"));
        
        assertTrue(hasCurrencyMismatch, "Currency-address mismatch should be detected");
    }
}