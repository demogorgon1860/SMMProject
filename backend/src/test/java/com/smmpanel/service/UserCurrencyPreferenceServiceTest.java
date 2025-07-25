package com.smmpanel.service;

import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.balance.CurrencyConversionResultDto;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ExchangeRateException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserCurrencyPreferenceServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrencyConversionService currencyConversionService;

    @Mock
    private CurrencyService currencyService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache userCurrencyCache;

    @InjectMocks
    private UserCurrencyPreferenceService userCurrencyPreferenceService;

    private final Long TEST_USER_ID = 1L;
    private final String DEFAULT_CURRENCY = "USD";
    private final String USER_PREFERRED_CURRENCY = "EUR";
    private final String TARGET_CURRENCY = "GBP";
    private final User testUser = User.builder()
            .id(TEST_USER_ID)
            .username("testuser")
            .preferredCurrency(USER_PREFERRED_CURRENCY)
            .build();

    @BeforeEach
    void setUp() {
        // Setup default currency
        when(currencyService.getDefaultCurrency()).thenReturn(DEFAULT_CURRENCY);
        
        // Setup supported currencies
        when(currencyService.getSupportedCurrencies())
                .thenReturn(Map.of(
                        "USD", new CurrencyService.CurrencyInfo("$", 2, "US Dollar", false),
                        "EUR", new CurrencyService.CurrencyInfo("€", 2, "Euro", false),
                        "GBP", new CurrencyService.CurrencyInfo("£", 2, "British Pound", false),
                        "JPY", new CurrencyService.CurrencyInfo("¥", 0, "Japanese Yen", false)
                ));
        
        // Setup cache manager
        when(cacheManager.getCache("userCurrency")).thenReturn(userCurrencyCache);
    }

    @Test
    void getUserPreferredCurrency_UserExists_ReturnsPreferredCurrency() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        
        String result = userCurrencyPreferenceService.getUserPreferredCurrency(TEST_USER_ID);
        
        assertEquals(USER_PREFERRED_CURRENCY, result);
        verify(userRepository).findById(TEST_USER_ID);
    }

    @Test
    void getUserPreferredCurrency_UserNotExists_ReturnsDefaultCurrency() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());
        
        String result = userCurrencyPreferenceService.getUserPreferredCurrency(TEST_USER_ID);
        
        assertEquals(DEFAULT_CURRENCY, result);
        verify(currencyService).getDefaultCurrency();
    }

    @Test
    void updateUserPreferredCurrency_ValidCurrency_UpdatesSuccessfully() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        
        userCurrencyPreferenceService.updateUserPreferredCurrency(TEST_USER_ID, TARGET_CURRENCY);
        
        assertEquals(TARGET_CURRENCY, testUser.getPreferredCurrency());
        verify(userRepository).save(testUser);
        verify(userCurrencyCache).evictIfPresent(TEST_USER_ID);
    }

    @Test
    void updateUserPreferredCurrency_InvalidCurrency_ThrowsException() {
        String invalidCurrency = "XYZ";
        
        assertThrows(ExchangeRateException.class, 
                () -> userCurrencyPreferenceService.updateUserPreferredCurrency(TEST_USER_ID, invalidCurrency));
        
        verify(userRepository, never()).save(any());
        verify(userCurrencyCache, never()).evictIfPresent(any());
    }

    @Test
    void updateUserPreferredCurrency_UserNotFound_ThrowsException() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());
        
        assertThrows(ResourceNotFoundException.class, 
                () -> userCurrencyPreferenceService.updateUserPreferredCurrency(TEST_USER_ID, TARGET_CURRENCY));
        
        verify(userRepository, never()).save(any());
        verify(userCurrencyCache, never()).evictIfPresent(any());
    }

    @Test
    void convertFromUserCurrency_ValidConversion_ReturnsResult() {
        BigDecimal amount = new BigDecimal("100");
        boolean format = true;
        
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        
        CurrencyConversionResultDto expectedResult = CurrencyConversionResultDto.builder()
                .amount(amount)
                .fromCurrency(USER_PREFERRED_CURRENCY)
                .toCurrency(TARGET_CURRENCY)
                .exchangeRate(new BigDecimal("0.85"))
                .convertedAmount(new BigDecimal("85.00"))
                .formattedAmount("£85.00")
                .build();
                
        when(currencyConversionService.convert(any(CurrencyConversionRequest.class)))
                .thenReturn(expectedResult);
        
        CurrencyConversionResultDto result = userCurrencyPreferenceService.convertFromUserCurrency(
                TEST_USER_ID, amount, TARGET_CURRENCY, format);
        
        assertNotNull(result);
        assertEquals(expectedResult, result);
        
        verify(currencyConversionService).convert(argThat(request -> 
                request.getAmount().compareTo(amount) == 0 &&
                request.getFromCurrency().equals(USER_PREFERRED_CURRENCY) &&
                request.getToCurrency().equals(TARGET_CURRENCY) &&
                request.isFormat() == format
        ));
    }

    @Test
    void convertToUserCurrency_ValidConversion_ReturnsResult() {
        BigDecimal amount = new BigDecimal("100");
        String sourceCurrency = "USD";
        boolean format = false;
        
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        
        CurrencyConversionResultDto expectedResult = CurrencyConversionResultDto.builder()
                .amount(amount)
                .fromCurrency(sourceCurrency)
                .toCurrency(USER_PREFERRED_CURRENCY)
                .exchangeRate(new BigDecimal("0.90"))
                .convertedAmount(new BigDecimal("90.00"))
                .build();
                
        when(currencyConversionService.convert(any(CurrencyConversionRequest.class)))
                .thenReturn(expectedResult);
        
        CurrencyConversionResultDto result = userCurrencyPreferenceService.convertToUserCurrency(
                TEST_USER_ID, amount, sourceCurrency, format);
        
        assertNotNull(result);
        assertEquals(expectedResult, result);
        
        verify(currencyConversionService).convert(argThat(request -> 
                request.getAmount().compareTo(amount) == 0 &&
                request.getFromCurrency().equals(sourceCurrency) &&
                request.getToCurrency().equals(USER_PREFERRED_CURRENCY) &&
                request.isFormat() == format
        ));
    }

    @Test
    void formatForUser_ValidAmount_ReturnsFormattedString() {
        BigDecimal amount = new BigDecimal("1234.56");
        String expectedFormatted = "1.234,56 €";
        
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(currencyService.formatCurrency(amount, USER_PREFERRED_CURRENCY))
                .thenReturn(expectedFormatted);
        
        String result = userCurrencyPreferenceService.formatForUser(TEST_USER_ID, amount);
        
        assertEquals(expectedFormatted, result);
    }

    @Test
    void getExchangeRateFromUserCurrency_ValidCurrencies_ReturnsRate() {
        BigDecimal expectedRate = new BigDecimal("0.85");
        
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(currencyConversionService.getExchangeRate(USER_PREFERRED_CURRENCY, TARGET_CURRENCY))
                .thenReturn(expectedRate);
        
        BigDecimal rate = userCurrencyPreferenceService.getExchangeRateFromUserCurrency(TEST_USER_ID, TARGET_CURRENCY);
        
        assertEquals(0, expectedRate.compareTo(rate));
    }

    @Test
    void getExchangeRateToUserCurrency_ValidCurrencies_ReturnsRate() {
        String sourceCurrency = "USD";
        BigDecimal expectedRate = new BigDecimal("1.18");
        
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        when(currencyConversionService.getExchangeRate(sourceCurrency, USER_PREFERRED_CURRENCY))
                .thenReturn(expectedRate);
        
        BigDecimal rate = userCurrencyPreferenceService.getExchangeRateToUserCurrency(TEST_USER_ID, sourceCurrency);
        
        assertEquals(0, expectedRate.compareTo(rate));
    }

    @Test
    void getSupportedCurrenciesWithUserPreference_ReturnsListWithUserPreferredMarked() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(testUser));
        
        List<CurrencyService.CurrencyInfo> result = 
                userCurrencyPreferenceService.getSupportedCurrenciesWithUserPreference(TEST_USER_ID);
        
        assertFalse(result.isEmpty());
        
        // Find the user's preferred currency in the result
        boolean foundPreferred = result.stream()
                .filter(CurrencyService.CurrencyInfo::isPreferred)
                .anyMatch(info -> info.getCode().equals(USER_PREFERRED_CURRENCY));
                
        assertTrue(foundPreferred, "User's preferred currency should be marked as preferred");
    }

    @Test
    void clearUserCurrencyCache_ValidUserId_EvictsCache() {
        userCurrencyPreferenceService.clearUserCurrencyCache(TEST_USER_ID);
        verify(userCurrencyCache).evictIfPresent(TEST_USER_ID);
    }
}
