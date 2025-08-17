package com.smmpanel.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.*;
import com.smmpanel.exception.BinomApiException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class BinomClientTest {

    @Mock private RestTemplate restTemplate;
    @Mock private ObjectMapper objectMapper;
    @Mock private io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    @Mock private io.github.resilience4j.retry.Retry readRetry;
    @Mock private io.github.resilience4j.retry.Retry writeRetry;

    private BinomClient binomClient;

    private static final String TEST_API_URL = "https://test-binom.com/public/api/v1";
    private static final String TEST_API_KEY = "test-api-key-123";

    @BeforeEach
    void setUp() {
        binomClient = new BinomClient(restTemplate, objectMapper, circuitBreaker, readRetry, writeRetry);
        ReflectionTestUtils.setField(binomClient, "apiUrl", TEST_API_URL);
        ReflectionTestUtils.setField(binomClient, "apiKey", TEST_API_KEY);

        // Mock circuit breaker and retry to execute directly
        when(circuitBreaker.executeSupplier(any())).thenAnswer(invocation -> {
            return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
        });
        when(readRetry.executeSupplier(any())).thenAnswer(invocation -> {
            return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
        });
        when(writeRetry.executeSupplier(any())).thenAnswer(invocation -> {
            return ((java.util.function.Supplier<?>) invocation.getArgument(0)).get();
        });
    }

    @Test
    @DisplayName("getOffersList should return offers list successfully")
    void testGetOffersList_Success() {
        // Arrange
        Map<String, Object> responseBody = new HashMap<>();
        List<Map<String, Object>> offersData = Arrays.asList(
            createOfferData("OFFER_001", "Test Offer 1", "https://example.com/offer1", "ACTIVE"),
            createOfferData("OFFER_002", "Test Offer 2", "https://example.com/offer2", "ACTIVE")
        );
        responseBody.put("data", offersData);

        ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act
        OffersListResponse result = binomClient.getOffersList();

        // Assert
        assertNotNull(result);
        assertEquals("SUCCESS", result.getStatus());
        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getOffers().size());

        OffersListResponse.OfferInfo offer1 = result.getOffers().get(0);
        assertEquals("OFFER_001", offer1.getOfferId());
        assertEquals("Test Offer 1", offer1.getName());
        assertEquals("https://example.com/offer1", offer1.getUrl());
        assertEquals("ACTIVE", offer1.getStatus());
    }

    @Test
    @DisplayName("getOffersList should handle API errors properly")
    void testGetOffersList_ApiError() {
        // Arrange
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Unauthorized access");
        errorResponse.put("error_code", "AUTH_001");

        ResponseEntity<Map> response = new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act & Assert
        BinomApiException exception = assertThrows(BinomApiException.class, 
                () -> binomClient.getOffersList());
        
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getHttpStatus());
        assertTrue(exception.getMessage().contains("Unauthorized"));
        assertEquals("AUTH_001", exception.getBinomErrorCode());
    }

    @Test
    @DisplayName("updateOffer should update offer successfully")
    void testUpdateOffer_Success() {
        // Arrange
        String offerId = "OFFER_123";
        UpdateOfferRequest request = UpdateOfferRequest.builder()
                .name("Updated Offer")
                .url("https://example.com/updated")
                .status("ACTIVE")
                .payout(15.0)
                .payoutCurrency("USD")
                .build();

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Offer updated successfully");

        ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act
        UpdateOfferResponse result = binomClient.updateOffer(offerId, request);

        // Assert
        assertNotNull(result);
        assertEquals(offerId, result.getOfferId());
        assertEquals("Updated Offer", result.getName());
        assertEquals("https://example.com/updated", result.getUrl());
        assertEquals("UPDATED", result.getStatus());
        assertTrue(result.getSuccess());
    }

    @Test
    @DisplayName("updateOffer should handle 404 not found errors")
    void testUpdateOffer_NotFound() {
        // Arrange
        String offerId = "OFFER_NONEXISTENT";
        UpdateOfferRequest request = UpdateOfferRequest.builder()
                .name("Updated Offer")
                .build();

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Offer not found");
        errorResponse.put("message", "The specified offer does not exist");

        ResponseEntity<Map> response = new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act & Assert
        BinomApiException exception = assertThrows(BinomApiException.class, 
                () -> binomClient.updateOffer(offerId, request));
        
        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatus());
        assertTrue(exception.getMessage().contains("Not found"));
        assertFalse(exception.isRetryable());
    }

    @Test
    @DisplayName("getCampaignInfo should return campaign information")
    void testGetCampaignInfo_Success() {
        // Arrange
        String campaignId = "CAMP_123";
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("name", "Test Campaign");
        responseBody.put("status", "ACTIVE");
        responseBody.put("traffic_source", "YouTube");
        responseBody.put("landing_page", "https://example.com/landing");
        responseBody.put("cost_model", "CPC");
        responseBody.put("cost_value", 0.25);
        responseBody.put("geo_targeting", "US");
        responseBody.put("is_active", true);
        responseBody.put("clicks", 1500);
        responseBody.put("conversions", 150);
        responseBody.put("cost", 375.0);
        responseBody.put("revenue", 450.0);
        responseBody.put("roi", 20.0);
        responseBody.put("ctr", 2.5);
        responseBody.put("cr", 10.0);

        ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act
        CampaignInfoResponse result = binomClient.getCampaignInfo(campaignId);

        // Assert
        assertNotNull(result);
        assertEquals(campaignId, result.getCampaignId());
        assertEquals("Test Campaign", result.getName());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals("YouTube", result.getTrafficSource());
        assertEquals("CPC", result.getCostModel());
        assertEquals(0.25, result.getCostValue());
        assertTrue(result.getIsActive());

        // Verify stats
        CampaignInfoResponse.CampaignStats stats = result.getStats();
        assertNotNull(stats);
        assertEquals(1500L, stats.getClicks());
        assertEquals(150L, stats.getConversions());
        assertEquals(375.0, stats.getCost());
        assertEquals(450.0, stats.getRevenue());
        assertEquals(20.0, stats.getRoi());
        assertEquals(2.5, stats.getCtr());
        assertEquals(10.0, stats.getCr());
    }

    @Test
    @DisplayName("setClickCost should set campaign click cost successfully")
    void testSetClickCost_Success() {
        // Arrange
        SetClickCostRequest request = SetClickCostRequest.builder()
                .campaignId("CAMP_123")
                .cost(BigDecimal.valueOf(0.30))
                .costModel("CPC")
                .currency("USD")
                .notes("Updated cost for better performance")
                .build();

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("success", true);
        responseBody.put("message", "Click cost updated successfully");

        ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act
        SetClickCostResponse result = binomClient.setClickCost(request);

        // Assert
        assertNotNull(result);
        assertEquals("CAMP_123", result.getCampaignId());
        assertEquals(BigDecimal.valueOf(0.30), result.getCost());
        assertEquals("CPC", result.getCostModel());
        assertEquals("USD", result.getCurrency());
        assertEquals("SUCCESS", result.getStatus());
        assertTrue(result.getSuccess());
    }

    @Test
    @DisplayName("setClickCost should handle validation errors")
    void testSetClickCost_ValidationError() {
        // Arrange
        SetClickCostRequest request = SetClickCostRequest.builder()
                .campaignId("CAMP_123")
                .cost(BigDecimal.valueOf(-1.0)) // Invalid negative cost
                .costModel("CPC")
                .currency("USD")
                .build();

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Invalid cost value");
        errorResponse.put("message", "Cost must be a positive number");
        errorResponse.put("error_code", "VALIDATION_001");

        ResponseEntity<Map> response = new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act & Assert
        BinomApiException exception = assertThrows(BinomApiException.class, 
                () -> binomClient.setClickCost(request));
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertTrue(exception.getMessage().contains("Bad request"));
        assertEquals("VALIDATION_001", exception.getBinomErrorCode());
        assertFalse(exception.isRetryable());
    }

    @Test
    @DisplayName("Enhanced error handling should handle rate limiting (418)")
    void testEnhancedErrorHandling_RateLimiting() {
        // Arrange
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Rate limit exceeded");
        errorResponse.put("message", "Too many requests, please try again later");

        ResponseEntity<Map> response = new ResponseEntity<>(errorResponse, HttpStatus.I_AM_A_TEAPOT);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act & Assert
        BinomApiException exception = assertThrows(BinomApiException.class, 
                () -> binomClient.getOffersList());
        
        assertEquals(HttpStatus.I_AM_A_TEAPOT, exception.getHttpStatus());
        assertTrue(exception.getMessage().contains("Service temporarily unavailable"));
        assertTrue(exception.isRetryable()); // 418 should be retryable
    }

    @Test
    @DisplayName("Enhanced error handling should handle forbidden access (403)")
    void testEnhancedErrorHandling_Forbidden() {
        // Arrange
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("error", "Insufficient permissions");
        errorResponse.put("message", "Your account does not have permission for this operation");

        ResponseEntity<Map> response = new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
        when(restTemplate.exchange(anyString(), any(), any(), eq(Map.class)))
                .thenReturn(response);

        // Act & Assert
        BinomApiException exception = assertThrows(BinomApiException.class, 
                () -> binomClient.getOffersList());
        
        assertEquals(HttpStatus.FORBIDDEN, exception.getHttpStatus());
        assertTrue(exception.getMessage().contains("Forbidden"));
        assertTrue(exception.isClientError());
        assertFalse(exception.isRetryable()); // 403 should not be retryable
    }

    private Map<String, Object> createOfferData(String id, String name, String url, String status) {
        Map<String, Object> offer = new HashMap<>();
        offer.put("id", id);
        offer.put("name", name);
        offer.put("url", url);
        offer.put("status", status);
        offer.put("type", "CPA");
        offer.put("category", "Finance");
        offer.put("payout", 10.0);
        offer.put("payout_currency", "USD");
        offer.put("payout_type", "CPA");
        offer.put("is_active", "ACTIVE".equals(status));
        offer.put("affiliate_network", "Test Network");
        return offer;
    }
}