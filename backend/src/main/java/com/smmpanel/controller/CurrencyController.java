package com.smmpanel.controller;

import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.balance.CurrencyConversionResponse;
import com.smmpanel.dto.response.ApiResponse;
import com.smmpanel.entity.User;
import com.smmpanel.security.CurrentUser;
import com.smmpanel.service.CurrencyConversionService;
import com.smmpanel.service.CurrencyService;
import com.smmpanel.service.UserCurrencyPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.smmpanel.util.Constants.API_BASE_PATH;

/**
 * Controller for handling currency-related operations including conversion, formatting,
 * and user preference management.
 */
@Slf4j
@RestController
@RequestMapping(API_BASE_PATH + "/currency")
@RequiredArgsConstructor
@Tag(name = "Currency", description = "Endpoints for currency conversion, formatting, and preference management")
@SecurityRequirement(name = "bearerAuth")
public class CurrencyController {

    private final CurrencyService currencyService;
    private final CurrencyConversionService currencyConversionService;
    private final UserCurrencyPreferenceService userCurrencyPreferenceService;

    /**
     * Get all supported currencies with their display information
     */
    @GetMapping("/supported")
    @Operation(summary = "Get supported currencies",
              description = "Retrieves a list of all supported currencies with their symbols and decimal places")
    public ResponseEntity<ApiResponse<Map<String, CurrencyService.CurrencyInfo>>> getSupportedCurrencies() {
        return ResponseEntity.ok(
            ApiResponse.success(currencyService.getSupportedCurrencies())
        );
    }
    
    /**
     * Get all supported currencies with user's preferred currency marked
     */
    @GetMapping("/supported/with-preference")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get supported currencies with user preference",
              description = "Retrieves a list of all supported currencies with the user's preferred currency marked")
    public ResponseEntity<ApiResponse<List<CurrencyService.CurrencyInfo>>> getSupportedCurrenciesWithPreference(
            @CurrentUser User user) {
                
        return ResponseEntity.ok(
            ApiResponse.success(userCurrencyPreferenceService.getSupportedCurrenciesWithUserPreference(user.getId()))
        );
    }

    /**
     * Convert an amount from one currency to another
     */
    @PostMapping("/convert")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Convert currency",
              description = "Converts an amount from one currency to another using current exchange rates")
    public ResponseEntity<ApiResponse<CurrencyConversionResponse>> convertCurrency(
            @CurrentUser User user,
            @Valid @RequestBody CurrencyConversionRequest request) {
                
        CurrencyConversionResponse response = currencyConversionService.convert(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Quick convert using query parameters (simpler for frontend use)
     */
    @GetMapping("/convert")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Quick convert",
              description = "Quick currency conversion using query parameters")
    public ResponseEntity<ApiResponse<CurrencyConversionResponse>> quickConvert(
            @CurrentUser User user,
            @RequestParam @NotBlank String from,
            @RequestParam @NotBlank String to,
            @RequestParam @NotNull @PositiveOrZero BigDecimal amount,
            @RequestParam(defaultValue = "true") boolean format) {
                
        CurrencyConversionRequest request = CurrencyConversionRequest.builder()
                .amount(amount)
                .fromCurrency(from)
                .toCurrency(to)
                .format(format)
                .build();
                
        CurrencyConversionResponse response = currencyConversionService.convert(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * Get user's preferred currency
     */
    @GetMapping("/preferences/currency")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get preferred currency",
              description = "Retrieves the user's preferred currency code")
    public ResponseEntity<ApiResponse<String>> getPreferredCurrency(
            @CurrentUser User user) {
                
        String currencyCode = userCurrencyPreferenceService.getUserPreferredCurrency(user.getId());
        return ResponseEntity.ok(ApiResponse.success(currencyCode));
    }
    
    /**
     * Update user's preferred currency
     */
    @PutMapping("/preferences/currency")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update preferred currency",
              description = "Updates the user's preferred currency for display purposes")
    @CacheEvict(value = "userCurrency", key = "#user.id")
    public ResponseEntity<ApiResponse<Void>> updatePreferredCurrency(
            @CurrentUser User user,
            @RequestParam @NotBlank String currencyCode) {
                
        userCurrencyPreferenceService.updateUserPreferredCurrency(user.getId(), currencyCode);
        return ResponseEntity.ok(ApiResponse.success(null, "Preferred currency updated to " + currencyCode));
    }
    
    /**
     * Get current exchange rates for a base currency
     */
    @GetMapping("/rates")
    @Operation(summary = "Get exchange rates",
              description = "Gets current exchange rates for all supported currencies")
    public ResponseEntity<ApiResponse<Map<String, BigDecimal>>> getExchangeRates(
            @RequestParam(defaultValue = "USD") String base) {
                
        // This would return the cached rates from ExchangeRateService
        // Implementation depends on how ExchangeRateService exposes this data
        return ResponseEntity.ok(ApiResponse.success(currencyService.getExchangeRates(base)));
    }
    
    /**
     * Format an amount in the user's preferred currency
     */
    @GetMapping("/format")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Format amount in user's currency",
              description = "Formats an amount in the user's preferred currency")
    public ResponseEntity<ApiResponse<String>> formatForUser(
            @CurrentUser User user,
            @RequestParam @NotNull @PositiveOrZero BigDecimal amount) {
                
        String formatted = userCurrencyPreferenceService.formatForUser(user.getId(), amount);
        return ResponseEntity.ok(ApiResponse.success(formatted));
    }
    
    /**
     * Convert and format an amount to the user's preferred currency
     */
    @GetMapping("/convert-to-user-currency")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Convert to user's currency",
              description = "Converts an amount to the user's preferred currency and formats it")
    public ResponseEntity<ApiResponse<String>> convertToUserCurrency(
            @CurrentUser User user,
            @RequestParam @NotBlank String fromCurrency,
            @RequestParam @NotNull @PositiveOrZero BigDecimal amount) {
                
        String result = userCurrencyPreferenceService.convertAndFormatForUser(
            user.getId(), amount, fromCurrency);
            
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
