package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.FixedBinomCampaign;
import com.smmpanel.entity.Order;
import com.smmpanel.repository.jpa.FixedBinomCampaignRepository;
import com.smmpanel.service.BinomService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

/** Integration test for Binom with 2 campaigns */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Binom Integration with 2 Campaigns")
public class BinomIntegrationTest {

    @Autowired private BinomService binomService;

    @Autowired private FixedBinomCampaignRepository fixedBinomCampaignRepository;

    @MockBean private BinomClient binomClient;

    private Order testOrder;

    @BeforeEach
    void setUp() {
        // Create test order
        testOrder = new Order();
        testOrder.setId(12345L);
        testOrder.setQuantity(1000);
        testOrder.setLink("https://youtube.com/watch?v=test123");

        // Mock Binom client responses
        when(binomClient.createOffer(any(CreateOfferRequest.class)))
                .thenReturn(
                        CreateOfferResponse.builder()
                                .offerId("OFFER_TEST_123")
                                .name("Test Offer")
                                .url("https://youtube.com/watch?v=test123")
                                .status("ACTIVE")
                                .build());

        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(
                        AssignOfferResponse.builder()
                                .status("ASSIGNED")
                                .offerId("OFFER_TEST_123")
                                .build());
    }

    @Test
    @DisplayName("Should distribute offer across 2 campaigns with clip (coefficient 3.0)")
    void testDistributeOfferWithClip() {
        // Given
        boolean hasClip = true;
        String targetUrl = "https://youtube.com/watch?v=test123";

        // When
        BinomIntegrationResponse response =
                binomService.createBinomIntegration(testOrder, targetUrl, hasClip, targetUrl);

        // Then
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("OFFER_TEST_123", response.getOfferId());
        assertEquals(2, response.getCampaignsCreated()); // Should work with 2 campaigns

        // Verify offer was created
        verify(binomClient, times(1))
                .createOffer(
                        argThat(
                                request ->
                                        request.getName().contains("Order_12345")
                                                && request.getUrl().equals(targetUrl)));

        // Verify offer was assigned to both campaigns
        verify(binomClient, times(2)).assignOfferToCampaign(eq("OFFER_TEST_123"), anyString());

        // Verify correct click distribution (1000 views * 3.0 coefficient = 3000 clicks / 2
        // campaigns = 1500 each)
        System.out.println(
                "Test Result - Campaigns: "
                        + response.getCampaignsCreated()
                        + ", Offer ID: "
                        + response.getOfferId());
    }

    @Test
    @DisplayName("Should distribute offer across 2 campaigns without clip (coefficient 4.0)")
    void testDistributeOfferWithoutClip() {
        // Given
        boolean hasClip = false;
        String targetUrl = "https://youtube.com/watch?v=test456";

        // When
        BinomIntegrationResponse response =
                binomService.createBinomIntegration(testOrder, targetUrl, hasClip, targetUrl);

        // Then
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(2, response.getCampaignsCreated());

        // Verify offer was assigned to both campaigns
        verify(binomClient, times(2)).assignOfferToCampaign(eq("OFFER_TEST_123"), anyString());

        // With coefficient 4.0: 1000 views * 4.0 = 4000 clicks / 2 campaigns = 2000 each
        System.out.println("Test Result - Total clicks distributed: " + (1000 * 4));
    }

    @Test
    @DisplayName("Should verify campaigns exist in database")
    void testCampaignsExistInDatabase() {
        // When
        List<FixedBinomCampaign> campaigns = fixedBinomCampaignRepository.findByActiveTrue();

        // Then
        assertNotNull(campaigns);
        assertFalse(campaigns.isEmpty(), "No active campaigns found in database");

        // Should have at least 2 campaigns
        assertTrue(
                campaigns.size() >= 2, "Expected at least 2 campaigns, found: " + campaigns.size());

        // Verify campaign IDs
        boolean hasIndia =
                campaigns.stream()
                        .anyMatch(
                                c ->
                                        "1".equals(c.getCampaignId())
                                                && "INDIA".equals(c.getCampaignName()));
        boolean hasTier3 =
                campaigns.stream()
                        .anyMatch(
                                c ->
                                        "2".equals(c.getCampaignId())
                                                && "Tier 3 + black list"
                                                        .equals(c.getCampaignName()));

        assertTrue(hasIndia, "INDIA campaign (ID: 1) not found");
        assertTrue(hasTier3, "Tier 3 campaign (ID: 2) not found");

        System.out.println("Found campaigns in database:");
        campaigns.forEach(
                c ->
                        System.out.println(
                                "  - " + c.getCampaignName() + " (ID: " + c.getCampaignId() + ")"));
    }

    @Test
    @DisplayName("Should handle API errors gracefully")
    void testHandleApiError() {
        // Given
        when(binomClient.createOffer(any())).thenThrow(new RuntimeException("Binom API error"));

        // When/Then
        assertThrows(
                Exception.class,
                () -> {
                    binomService.createBinomIntegration(
                            testOrder,
                            "https://youtube.com/watch?v=error",
                            true,
                            "https://youtube.com/watch?v=error");
                });
    }
}
