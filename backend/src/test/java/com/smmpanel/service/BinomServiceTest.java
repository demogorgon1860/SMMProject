package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.BinomCampaignRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BinomServiceTest {

    @Mock private BinomClient binomClient;
    @Mock private BinomCampaignRepository binomCampaignRepository;
    @Mock private OrderRepository orderRepository;

    @InjectMocks private BinomService binomService;

    private Order testOrder;
    private List<BinomCampaign> testCampaigns;
    private BinomIntegrationRequest testRequest;

    private static final Long TEST_ORDER_ID = 123L;
    private static final String TEST_CAMPAIGN_ID_1 = "CAMP_001";
    private static final String TEST_CAMPAIGN_ID_2 = "CAMP_002";
    private static final String TEST_CAMPAIGN_ID_3 = "CAMP_003";
    private static final String TEST_OFFER_ID = "OFFER_123";
    private static final String TEST_TARGET_URL = "https://youtube.com/watch?v=test123";

    @BeforeEach
    void setUp() {
        // Create test order
        testOrder = new Order();
        testOrder.setId(TEST_ORDER_ID);
        testOrder.setQuantity(1000);
        testOrder.setLink(TEST_TARGET_URL);
        testOrder.setStatus(OrderStatus.ACTIVE);
        testOrder.setTargetViews(1000);
        testOrder.setTargetCountry("US");

        // Create test campaigns for 3-campaign distribution
        testCampaigns =
                Arrays.asList(
                        createBinomCampaign(1L, TEST_CAMPAIGN_ID_1, TEST_ORDER_ID, true),
                        createBinomCampaign(2L, TEST_CAMPAIGN_ID_2, TEST_ORDER_ID, true),
                        createBinomCampaign(3L, TEST_CAMPAIGN_ID_3, TEST_ORDER_ID, true));

        // Create test integration request
        testRequest =
                BinomIntegrationRequest.builder()
                        .orderId(TEST_ORDER_ID)
                        .targetViews(1000)
                        .targetUrl(TEST_TARGET_URL)
                        .clipCreated(true)
                        .coefficient(BigDecimal.valueOf(3.0))
                        .geoTargeting("US")
                        .build();
    }

    private BinomCampaign createBinomCampaign(
            Long id, String campaignId, Long orderId, boolean active) {
        BinomCampaign campaign = new BinomCampaign();
        campaign.setId(id);
        campaign.setCampaignId(campaignId);
        campaign.setOrderId(orderId);
        campaign.setActive(active);
        campaign.setStatus("ACTIVE");
        campaign.setClicksDelivered(100);
        campaign.setConversions(10);
        campaign.setCost(BigDecimal.valueOf(50.0));
        campaign.setRevenue(BigDecimal.valueOf(100.0));
        campaign.setCreatedAt(LocalDateTime.now());
        campaign.setUpdatedAt(LocalDateTime.now());
        return campaign;
    }

    @Test
    @DisplayName("createBinomIntegration should create integration with 3-campaign distribution")
    void testCreateBinomIntegration_ThreeCampaignDistribution() {
        // Arrange
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        CreateOfferResponse offerResponse =
                CreateOfferResponse.builder()
                        .offerId(TEST_OFFER_ID)
                        .name("Test Offer")
                        .url(TEST_TARGET_URL)
                        .status("ACTIVE")
                        .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(offerResponse);

        // Campaigns are pre-configured, no need to mock campaign creation

        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(
                        AssignOfferResponse.builder()
                                .campaignId("test")
                                .offerId(TEST_OFFER_ID)
                                .status("ASSIGNED")
                                .build());

        when(binomCampaignRepository.save(any(BinomCampaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        BinomIntegrationResponse response = binomService.createBinomIntegration(testRequest);

        // Assert
        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals(TEST_OFFER_ID, response.getOfferId());
        assertEquals(3, response.getCampaignsCreated());
        assertNotNull(response.getCampaignIds());
        assertEquals(3, response.getCampaignIds().size());

        // Verify 3 campaigns were created
        // Verify offer was created and assigned
        verify(binomClient, times(1)).createOffer(any(CreateOfferRequest.class));
        verify(binomClient, times(3)).assignOfferToCampaign(eq(TEST_OFFER_ID), anyString());
        verify(binomCampaignRepository, times(3)).save(any(BinomCampaign.class));
    }

    @Test
    @DisplayName("createBinomIntegration should handle coefficient calculation correctly")
    void testCreateBinomIntegration_CoefficientCalculation() {
        // Arrange - Test with clip creation (coefficient 3.0)
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        CreateOfferResponse offerResponse =
                CreateOfferResponse.builder()
                        .offerId(TEST_OFFER_ID)
                        .name("Test Offer")
                        .url(TEST_TARGET_URL)
                        .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(offerResponse);

        // Campaigns are pre-configured, no dynamic creation
        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());
        when(binomCampaignRepository.save(any(BinomCampaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        BinomIntegrationResponse response = binomService.createBinomIntegration(testRequest);

        // Assert
        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());

        // Verify offers were assigned to fixed campaigns
        verify(binomClient, times(3)).assignOfferToCampaign(anyString(), anyString());
    }

    @Test
    @DisplayName("getCampaignStatsForOrder should aggregate stats from 3 campaigns")
    void testGetCampaignStatsForOrder_AggregateStats() {
        // Arrange
        when(binomCampaignRepository.findByOrderIdAndActiveTrue(TEST_ORDER_ID))
                .thenReturn(testCampaigns);

        // Mock individual campaign stats
        CampaignStatsResponse stats1 =
                CampaignStatsResponse.builder()
                        .campaignId(TEST_CAMPAIGN_ID_1)
                        .clicks(100L)
                        .conversions(10L)
                        .cost(BigDecimal.valueOf(50.0))
                        .revenue(BigDecimal.valueOf(80.0))
                        .build();

        CampaignStatsResponse stats2 =
                CampaignStatsResponse.builder()
                        .campaignId(TEST_CAMPAIGN_ID_2)
                        .clicks(150L)
                        .conversions(15L)
                        .cost(BigDecimal.valueOf(75.0))
                        .revenue(BigDecimal.valueOf(120.0))
                        .build();

        CampaignStatsResponse stats3 =
                CampaignStatsResponse.builder()
                        .campaignId(TEST_CAMPAIGN_ID_3)
                        .clicks(200L)
                        .conversions(20L)
                        .cost(BigDecimal.valueOf(100.0))
                        .revenue(BigDecimal.valueOf(160.0))
                        .build();

        when(binomClient.getCampaignStats(TEST_CAMPAIGN_ID_1)).thenReturn(stats1);
        when(binomClient.getCampaignStats(TEST_CAMPAIGN_ID_2)).thenReturn(stats2);
        when(binomClient.getCampaignStats(TEST_CAMPAIGN_ID_3)).thenReturn(stats3);

        // Act
        CampaignStatsResponse aggregatedStats =
                binomService.getCampaignStatsForOrder(TEST_ORDER_ID);

        // Assert
        assertNotNull(aggregatedStats);
        assertEquals(450L, aggregatedStats.getClicks()); // 100 + 150 + 200
        assertEquals(45L, aggregatedStats.getConversions()); // 10 + 15 + 20
        assertEquals(BigDecimal.valueOf(225.0), aggregatedStats.getCost()); // 50 + 75 + 100
        assertEquals(BigDecimal.valueOf(360.0), aggregatedStats.getRevenue()); // 80 + 120 + 160
        assertEquals("ACTIVE", aggregatedStats.getStatus()); // All 3 campaigns active
        assertTrue(aggregatedStats.getCampaignId().contains(TEST_CAMPAIGN_ID_1));
        assertTrue(aggregatedStats.getCampaignId().contains(TEST_CAMPAIGN_ID_2));
        assertTrue(aggregatedStats.getCampaignId().contains(TEST_CAMPAIGN_ID_3));
    }

    @Test
    @DisplayName("stopAllCampaignsForOrder should stop all 3 campaigns")
    void testStopAllCampaignsForOrder_ThreeCampaigns() {
        // Arrange
        when(binomCampaignRepository.findByOrderIdAndActiveTrue(TEST_ORDER_ID))
                .thenReturn(testCampaigns);

        // Act
        binomService.stopAllCampaignsForOrder(TEST_ORDER_ID);

        // Assert
        // Campaigns remain active, no stop operations
        verify(binomCampaignRepository, times(3)).save(any(BinomCampaign.class));

        // Verify campaigns were marked as inactive
        for (BinomCampaign campaign : testCampaigns) {
            verify(binomCampaignRepository)
                    .save(
                            argThat(
                                    savedCampaign ->
                                            savedCampaign
                                                            .getCampaignId()
                                                            .equals(campaign.getCampaignId())
                                                    && !savedCampaign.isActive()));
        }
    }

    @Test
    @DisplayName("createBinomIntegration should handle partial campaign failures")
    void testCreateBinomIntegration_PartialFailures() {
        // Arrange
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        CreateOfferResponse offerResponse =
                CreateOfferResponse.builder()
                        .offerId(TEST_OFFER_ID)
                        .name("Test Offer")
                        .url(TEST_TARGET_URL)
                        .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(offerResponse);

        // Campaigns are pre-configured
        // Simulate failure for one assignment

        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());
        when(binomCampaignRepository.save(any(BinomCampaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        BinomIntegrationResponse response = binomService.createBinomIntegration(testRequest);

        // Assert
        assertNotNull(response);
        assertEquals("PARTIAL_SUCCESS", response.getStatus());
        assertEquals(2, response.getCampaignsCreated()); // Only 2 out of 3 succeeded
        assertNotNull(response.getMessage());
        assertTrue(response.getMessage().contains("2 out of 3 campaigns"));
    }

    @Test
    @DisplayName("getCampaignStatsForOrder should handle missing campaigns gracefully")
    void testGetCampaignStatsForOrder_MissingCampaigns() {
        // Arrange - Only 2 campaigns instead of expected 3
        List<BinomCampaign> partialCampaigns =
                Arrays.asList(testCampaigns.get(0), testCampaigns.get(1));
        when(binomCampaignRepository.findByOrderIdAndActiveTrue(TEST_ORDER_ID))
                .thenReturn(partialCampaigns);

        CampaignStatsResponse stats1 =
                CampaignStatsResponse.builder()
                        .campaignId(TEST_CAMPAIGN_ID_1)
                        .clicks(100L)
                        .conversions(10L)
                        .cost(BigDecimal.valueOf(50.0))
                        .revenue(BigDecimal.valueOf(80.0))
                        .build();

        CampaignStatsResponse stats2 =
                CampaignStatsResponse.builder()
                        .campaignId(TEST_CAMPAIGN_ID_2)
                        .clicks(150L)
                        .conversions(15L)
                        .cost(BigDecimal.valueOf(75.0))
                        .revenue(BigDecimal.valueOf(120.0))
                        .build();

        when(binomClient.getCampaignStats(TEST_CAMPAIGN_ID_1)).thenReturn(stats1);
        when(binomClient.getCampaignStats(TEST_CAMPAIGN_ID_2)).thenReturn(stats2);

        // Act
        CampaignStatsResponse aggregatedStats =
                binomService.getCampaignStatsForOrder(TEST_ORDER_ID);

        // Assert
        assertNotNull(aggregatedStats);
        assertEquals(250L, aggregatedStats.getClicks()); // 100 + 150
        assertEquals(25L, aggregatedStats.getConversions()); // 10 + 15
        assertEquals("PARTIAL", aggregatedStats.getStatus()); // Not all 3 campaigns
    }

    @Test
    @DisplayName(
            "createBinomIntegration should handle coefficient calculation without clip creation")
    void testCreateBinomIntegration_NoCoefficientWithoutClip() {
        // Arrange - Test without clip creation (coefficient 4.0)
        BinomIntegrationRequest requestWithoutClip =
                BinomIntegrationRequest.builder()
                        .orderId(TEST_ORDER_ID)
                        .targetViews(1000)
                        .targetUrl(TEST_TARGET_URL)
                        .clipCreated(false)
                        .coefficient(BigDecimal.valueOf(4.0))
                        .geoTargeting("US")
                        .build();

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        CreateOfferResponse offerResponse =
                CreateOfferResponse.builder()
                        .offerId(TEST_OFFER_ID)
                        .name("Test Offer")
                        .url(TEST_TARGET_URL)
                        .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(offerResponse);

        // Campaigns are pre-configured, no dynamic creation
        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());
        when(binomCampaignRepository.save(any(BinomCampaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        BinomIntegrationResponse response = binomService.createBinomIntegration(requestWithoutClip);

        // Assert
        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());

        // Verify offers were assigned to fixed campaigns
        verify(binomClient, times(3)).assignOfferToCampaign(anyString(), anyString());
    }
}
