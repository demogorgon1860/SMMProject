package com.smmpanel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.balance.CurrencyConversionRequest;
import com.smmpanel.dto.balance.CurrencyConversionResultDto;
import com.smmpanel.dto.response.ApiResponse;
import com.smmpanel.entity.User;
import com.smmpanel.security.CurrentUser;
import com.smmpanel.service.CurrencyConversionService;
import com.smmpanel.service.CurrencyService;
import com.smmpanel.service.UserCurrencyPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@ExtendWith(MockitoExtension.class)
class CurrencyControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CurrencyService currencyService;
    
    @Mock
    private CurrencyConversionService currencyConversionService;
    
    @Mock
    private UserCurrencyPreferenceService userCurrencyPreferenceService;
    
    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private CurrencyController currencyController;

    private final User testUser = User.builder()
            .id(1L)
            .username("testuser")
            .email("test@example.com")
            .preferredCurrency("EUR")
            .build();
            
    private final CurrencyService.CurrencyInfo usdInfo = new CurrencyService.CurrencyInfo("$", 2, "US Dollar", false);
    private final CurrencyService.CurrencyInfo eurInfo = new CurrencyService.CurrencyInfo("€", 2, "Euro", true);
    private final CurrencyService.CurrencyInfo gbpInfo = new CurrencyService.CurrencyInfo("£", 2, "British Pound", false);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(currencyController).build();
        
        // Setup default currency service responses
        when(currencyService.getDefaultCurrency()).thenReturn("USD");
        when(currencyService.getSupportedCurrencies())
                .thenReturn(Map.of(
                        "USD", usdInfo,
                        "EUR", eurInfo,
                        "GBP", gbpInfo
                ));
    }

    @Test
    void getSupportedCurrencies_ReturnsOk() throws Exception {
        when(currencyService.getSupportedCurrencies())
                .thenReturn(Map.of("USD", new CurrencyService.CurrencyInfo("$", 2, "US Dollar")));

        mockMvc.perform(get("/api/currency/supported"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.USD").exists())
                .andExpect(jsonPath("$.data.USD.symbol").value("$"));
    }

    @Test
    void getSupportedCurrencies_ShouldReturnListOfCurrencies() throws Exception {
        mockMvc.perform(get("/api/currency/supported")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.USD").exists())
                .andExpect(jsonPath("$.data.USD.symbol").value("$"))
                .andExpect(jsonPath("$.data.EUR").exists())
                .andExpect(jsonPath("$.data.GBP").exists());
    }
    
    @Test
    void getSupportedCurrenciesWithPreference_ShouldMarkUserPreferredCurrency() throws Exception {
        when(userCurrencyPreferenceService.getSupportedCurrenciesWithUserPreference(anyLong()))
                .thenReturn(List.of(
                        new CurrencyService.CurrencyInfo("$", 2, "US Dollar", false),
                        new CurrencyService.CurrencyInfo("€", 2, "Euro", true),
                        new CurrencyService.CurrencyInfo("£", 2, "British Pound", false)
                ));
                
        mockMvc.perform(get("/api/currency/supported/with-preference")
                        .with(request -> {
                            request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, testUser);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.preferred == true)]").exists())
                .andExpect(jsonPath("$.data[?(@.preferred == false)]").exists());
    }

    @Test
    void convertCurrency_ValidRequest_ReturnsConvertedAmount() throws Exception {
        CurrencyConversionRequest request = new CurrencyConversionRequest(
                new BigDecimal("100"), "USD", "EUR", true);
        
        when(currencyService.convertCurrency(any(BigDecimal.class), anyString(), anyString()))
                .thenReturn(new BigDecimal("85.00"));
        when(currencyService.formatCurrency(any(BigDecimal.class), anyString()))
                .thenReturn("85.00 €");

        mockMvc.perform(post("/api/currency/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "amount": 100,
                                "fromCurrency": "USD",
                                "toCurrency": "EUR",
                                "format": true
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.convertedAmount").isNumber())
                .andExpect(jsonPath("$.data.formattedAmount").isString());
    }

    @Test
    void convertCurrency_ShouldConvertAmount() throws Exception {
        CurrencyConversionRequest request = new CurrencyConversionRequest();
        request.setAmount(new BigDecimal("100"));
        request.setFromCurrency("USD");
        request.setToCurrency("EUR");
        request.setFormat(true);
        
        CurrencyConversionResultDto response = CurrencyConversionResultDto.builder()
                .amount(new BigDecimal("100"))
                .fromCurrency("USD")
                .toCurrency("EUR")
                .exchangeRate(new BigDecimal("0.85"))
                .convertedAmount(new BigDecimal("85.00"))
                .formattedAmount("85.00 €")
                .build();

        when(currencyConversionService.convert(any(CurrencyConversionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/currency/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(100))
                .andExpect(jsonPath("$.data.fromCurrency").value("USD"))
                .andExpect(jsonPath("$.data.toCurrency").value("EUR"))
                .andExpect(jsonPath("$.data.convertedAmount").value(85.00))
                .andExpect(jsonPath("$.data.formattedAmount").value("85.00 €"));
    }
    
    @Test
    void quickConvert_ShouldConvertUsingQueryParams() throws Exception {
        CurrencyConversionResultDto response = CurrencyConversionResultDto.builder()
                .amount(new BigDecimal("100"))
                .fromCurrency("USD")
                .toCurrency("EUR")
                .exchangeRate(new BigDecimal("0.85"))
                .convertedAmount(new BigDecimal("85.00"))
                .formattedAmount("85.00 €")
                .build();
                
        when(currencyConversionService.convert(any(CurrencyConversionRequest.class)))
                .thenReturn(response);

        mockMvc.perform(get("/api/currency/convert")
                        .param("from", "USD")
                        .param("to", "EUR")
                        .param("amount", "100")
                        .param("format", "true")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(100))
                .andExpect(jsonPath("$.data.fromCurrency").value("USD"));
    }

    @Test
    void quickConvert_ValidRequest_ReturnsConvertedAmount() throws Exception {
        when(currencyService.convertCurrency(any(BigDecimal.class), anyString(), anyString()))
                .thenReturn(new BigDecimal("8500.00"));
        when(currencyService.formatCurrency(any(BigDecimal.class), anyString()))
                .thenReturn("8,500.00 ¥");

        mockMvc.perform(get("/api/currency/convert")
                        .param("from", "USD")
                        .param("to", "JPY")
                        .param("amount", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.amount").value(100))
                .andExpect(jsonPath("$.data.fromCurrency").value("USD"))
                .andExpect(jsonPath("$.data.toCurrency").value("JPY"));
    }

    @Test
    void getPreferredCurrency_ShouldReturnUserPreferredCurrency() throws Exception {
        when(userCurrencyPreferenceService.getUserPreferredCurrency(anyLong()))
                .thenReturn("EUR");
                
        mockMvc.perform(get("/api/currency/preferences/currency")
                        .with(request -> {
                            request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, testUser);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("EUR"));
    }
    
    @Test
    void updatePreferredCurrency_ShouldUpdateUserPreference() throws Exception {
        doNothing().when(userCurrencyPreferenceService)
                .updateUserPreferredCurrency(anyLong(), eq("EUR"));
                
        mockMvc.perform(put("/api/currency/preferences/currency")
                        .param("currencyCode", "EUR")
                        .with(request -> {
                            request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, testUser);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Preferred currency updated to EUR"));
                
        verify(userCurrencyPreferenceService).updateUserPreferredCurrency(testUser.getId(), "EUR");
    }
    
    @Test
    void formatForUser_ShouldFormatAmountInUserCurrency() throws Exception {
        when(userCurrencyPreferenceService.formatForUser(anyLong(), any(BigDecimal.class)))
                .thenReturn("1.234,56 €");
                
        mockMvc.perform(get("/api/currency/format")
                        .param("amount", "1234.56")
                        .with(request -> {
                            request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, testUser);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("1.234,56 €"));
    }
    
    @Test
    void convertToUserCurrency_ShouldConvertAndFormatForUser() throws Exception {
        when(userCurrencyPreferenceService.formatForUser(anyLong(), any(BigDecimal.class)))
            .thenReturn("1.234,56 €");
            
        mockMvc.perform(get("/api/currency/convert-to-user-currency")
                        .param("fromCurrency", "USD")
                        .param("amount", "1500.00")
                        .with(request -> {
                            request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, testUser);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("1.234,56 €"));
    }

    @Test
    void updatePreferredCurrency_ValidCurrency_ReturnsSuccess() throws Exception {
        mockMvc.perform(put("/api/currency/preferences")
                        .param("currencyCode", "EUR")
                        .with(request -> {
                            request.setAttribute(CurrentUser.REQUEST_ATTRIBUTE, testUser);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Preferred currency updated"));
    }

    @Test
    void getExchangeRates_ReturnsRates() throws Exception {
        mockMvc.perform(get("/api/currency/rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void convertCurrency_InvalidCurrency_ReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/currency/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "amount": -100,
                                "fromCurrency": "INVALID",
                                "toCurrency": "EUR"
                            }
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quickConvert_MissingParameters_ReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/currency/convert"))
                .andExpect(status().isBadRequest());
    }
}
