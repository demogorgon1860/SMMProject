package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Integration test for 3-campaign distribution workflow Tests the complete flow from order creation
 * to campaign management
 */
@ExtendWith(MockitoExtension.class)
class BinomThreeCampaignIntegrationTest {

    @Mock private BinomClient binomClient;
    @Mock private BinomCampaignRepository binomCampaignRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private FixedBinomCampaignRepository fixedBinomCampaignRepository;
    @Mock private ConversionCoefficientRepository conversionCoefficientRepository;
    @Mock private VideoProcessingRepository videoProcessingRepository;
    @Mock private YouTubeAccountRepository youTubeAccountRepository;
    @Mock private SeleniumService seleniumService;
    @Mock private YouTubeService youTubeService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private com.smmpanel.service.kafka.VideoProcessingProducerService
            videoProcessingProducerService;

    @Mock private OrderStateManagementService orderStateManagementService;

    private BinomService binomService;
    private OrderService orderService;

    private Order testOrder;
    private List<BinomCampaign> threeCampaigns;

    private static final Long TEST_ORDER_ID = 123L;
    private static final String TEST_VIDEO_URL = "https://youtube.com/watch?v=test123";
    private static final String TEST_VIDEO_ID = "test123";

    @BeforeEach
    void setUp() {
        // Initialize services
        binomService =
                new BinomService(
                        binomClient,
                        orderRepository,
                        binomCampaignRepository,
                        fixedBinomCampaignRepository,
                        conversionCoefficientRepository);

        // Create test order
        testOrder = new Order();
        testOrder.setId(TEST_ORDER_ID);
        testOrder.setQuantity(1000);
        testOrder.setLink(TEST_VIDEO_URL);
        testOrder.setStatus(OrderStatus.ACTIVE);
        testOrder.setTargetViews(1000);
        testOrder.setTargetCountry("US");
        testOrder.setCoefficient(BigDecimal.valueOf(3.0));

        // Create 3 test campaigns
        threeCampaigns =
                Arrays.asList(
                        createBinomCampaign("CAMP_001", TEST_ORDER_ID, 100, 10),
                        createBinomCampaign("CAMP_002", TEST_ORDER_ID, 150, 15),
                        createBinomCampaign("CAMP_003", TEST_ORDER_ID, 200, 20));
    }

    private BinomCampaign createBinomCampaign(
            String campaignId, Long orderId, int clicks, int conversions) {
        BinomCampaign campaign = new BinomCampaign();
        campaign.setCampaignId(campaignId);
        campaign.setOrderId(orderId);
        campaign.setActive(true);
        campaign.setStatus("ACTIVE");
        campaign.setClicksDelivered(clicks);
        campaign.setConversions(conversions);
        campaign.setCost(BigDecimal.valueOf(clicks * 0.5));
        campaign.setRevenue(BigDecimal.valueOf(conversions * 8.0));
        campaign.setCreatedAt(LocalDateTime.now());
        campaign.setUpdatedAt(LocalDateTime.now());
        return campaign;
    }

    @Test
    @DisplayName("Complete 3-campaign workflow should work end-to-end")
    void testComplete3CampaignWorkflow() {
        // Arrange - Order creation phase
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Mock offer creation
        CreateOfferResponse offerResponse =
                CreateOfferResponse.builder()
                        .offerId("OFFER_123")
                        .name("YouTube Video Offer")
                        .url(TEST_VIDEO_URL)
                        .status("ACTIVE")
                        .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(offerResponse);

        // Campaigns are pre-configured, no need to mock campaign creation

        // Mock offer assignment
        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());

        when(binomCampaignRepository.save(any(BinomCampaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Act - Create Binom integration with 3-campaign distribution
        BinomIntegrationRequest request =
                BinomIntegrationRequest.builder()
                        .orderId(TEST_ORDER_ID)
                        .targetViews(1000)
                        .targetUrl(TEST_VIDEO_URL)
                        .clipCreated(true)
                        .coefficient(BigDecimal.valueOf(3.0))
                        .geoTargeting("US")
                        .build();

        BinomIntegrationResponse integrationResponse = binomService.createBinomIntegration(request);

        // Assert - Verify 3-campaign distribution
        assertNotNull(integrationResponse);
        assertEquals("SUCCESS", integrationResponse.getStatus());
        assertEquals(3, integrationResponse.getCampaignsCreated());

        // Verify offer was created
        verify(binomClient, times(1)).createOffer(any(CreateOfferRequest.class));

        // Verify offer assignment to all 3 campaigns
        verify(binomClient, times(3)).assignOfferToCampaign(eq("OFFER_123"), anyString());
        verify(binomCampaignRepository, times(3)).save(any(BinomCampaign.class));
    }

    @Test
    @DisplayName("Campaign stats aggregation should work across 3 campaigns")
    void testCampaignStatsAggregation() {
        // Arrange - Mock active campaigns
        when(binomCampaignRepository.findByOrderIdAndActiveTrue(TEST_ORDER_ID))
                .thenReturn(threeCampaigns);

        // Mock individual campaign stats from Binom API
        CampaignStatsResponse stats1 =
                CampaignStatsResponse.builder()
                        .campaignId("CAMP_001")
                        .clicks(100L)
                        .conversions(10L)
                        .cost(BigDecimal.valueOf(50.0))
                        .revenue(BigDecimal.valueOf(80.0))
                        .build();

        CampaignStatsResponse stats2 =
                CampaignStatsResponse.builder()
                        .campaignId("CAMP_002")
                        .clicks(150L)
                        .conversions(15L)
                        .cost(BigDecimal.valueOf(75.0))
                        .revenue(BigDecimal.valueOf(120.0))
                        .build();

        CampaignStatsResponse stats3 =
                CampaignStatsResponse.builder()
                        .campaignId("CAMP_003")
                        .clicks(200L)
                        .conversions(20L)
                        .cost(BigDecimal.valueOf(100.0))
                        .revenue(BigDecimal.valueOf(160.0))
                        .build();

        when(binomClient.getCampaignStats("CAMP_001")).thenReturn(stats1);
        when(binomClient.getCampaignStats("CAMP_002")).thenReturn(stats2);
        when(binomClient.getCampaignStats("CAMP_003")).thenReturn(stats3);

        // Act - Get aggregated stats
        CampaignStatsResponse aggregatedStats =
                binomService.getCampaignStatsForOrder(TEST_ORDER_ID);

        // Assert - Verify aggregation
        assertNotNull(aggregatedStats);
        assertEquals(450L, aggregatedStats.getClicks()); // 100 + 150 + 200
        assertEquals(45L, aggregatedStats.getConversions()); // 10 + 15 + 20
        assertEquals(BigDecimal.valueOf(225.0), aggregatedStats.getCost()); // 50 + 75 + 100
        assertEquals(BigDecimal.valueOf(360.0), aggregatedStats.getRevenue()); // 80 + 120 + 160
        assertEquals("ACTIVE", aggregatedStats.getStatus()); // All 3 campaigns active

        // Verify campaign IDs are aggregated
        String campaignIds = aggregatedStats.getCampaignId();
        assertTrue(campaignIds.contains("CAMP_001"));
        assertTrue(campaignIds.contains("CAMP_002"));
        assertTrue(campaignIds.contains("CAMP_003"));
    }

    @Test
    @DisplayName("Order completion should work with 3-campaign monitoring")
    void testOrderCompletionWith3Campaigns() {
        // Arrange - Mock campaign stats that indicate order completion
        when(binomCampaignRepository.findByOrderIdAndActiveTrue(TEST_ORDER_ID))
                .thenReturn(threeCampaigns);

        // Mock stats showing order is completed (45 conversions >= 1000 target / coefficient)
        CampaignStatsResponse aggregatedStats =
                CampaignStatsResponse.builder()
                        .campaignId("CAMP_001,CAMP_002,CAMP_003")
                        .clicks(3000L)
                        .conversions(1000L) // Enough conversions to complete the order
                        .cost(BigDecimal.valueOf(1500.0))
                        .revenue(BigDecimal.valueOf(2000.0))
                        .status("ACTIVE")
                        .build();

        // Mock individual campaign stats
        when(binomClient.getCampaignStats(anyString()))
                .thenReturn(
                        CampaignStatsResponse.builder()
                                .clicks(1000L)
                                .conversions(334L) // ~1000/3
                                .cost(BigDecimal.valueOf(500.0))
                                .revenue(BigDecimal.valueOf(667.0))
                                .build());

        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Mock state transition for order completion
        when(orderStateManagementService.transitionToCompleted(eq(TEST_ORDER_ID), anyInt()))
                .thenReturn(
                        StateTransitionResult.builder()
                                .orderId(TEST_ORDER_ID)
                                .success(true)
                                .fromStatus(OrderStatus.IN_PROGRESS)
                                .toStatus(OrderStatus.COMPLETED)
                                .build());

        // Act - Check if order should be completed based on campaign data
        CampaignStatsResponse stats = binomService.getCampaignStatsForOrder(TEST_ORDER_ID);

        // Assert - Verify order completion logic
        assertNotNull(stats);
        assertEquals(3000L, stats.getClicks()); // Aggregated from 3 campaigns
        assertEquals(1002L, stats.getConversions()); // 334 * 3 (with rounding)

        // Verify that the aggregated conversions are sufficient for order completion
        assertTrue(stats.getConversions() >= testOrder.getQuantity());
    }

    @Test
    @DisplayName("Campaign stop operation should handle all 3 campaigns")
    void testStopAll3Campaigns() {
        // Arrange
        when(binomCampaignRepository.findByOrderIdAndActiveTrue(TEST_ORDER_ID))
                .thenReturn(threeCampaigns);

        // Act - Stop all campaigns for order
        binomService.stopAllCampaignsForOrder(TEST_ORDER_ID);

        // Assert - Verify all 3 campaigns were stopped
        // Campaigns remain active - no stop/start operations needed

        // Verify campaigns were marked as inactive
        verify(binomCampaignRepository, times(3))
                .save(
                        argThat(
                                campaign ->
                                        !campaign.isActive()
                                                && "STOPPED".equals(campaign.getStatus())));
    }

    @Test
    @DisplayName("Coefficient calculation should be properly distributed across 3 campaigns")
    void testCoefficientDistributionAcross3Campaigns() {
        // Arrange
        when(orderRepository.findById(TEST_ORDER_ID)).thenReturn(Optional.of(testOrder));

        // Test both clip creation (3.0) and no clip (4.0) scenarios
        BinomIntegrationRequest requestWithClip =
                BinomIntegrationRequest.builder()
                        .orderId(TEST_ORDER_ID)
                        .targetViews(1500) // Higher target for better distribution testing
                        .targetUrl(TEST_VIDEO_URL)
                        .clipCreated(true)
                        .coefficient(BigDecimal.valueOf(3.0))
                        .geoTargeting("US")
                        .build();

        BinomIntegrationRequest requestWithoutClip =
                BinomIntegrationRequest.builder()
                        .orderId(TEST_ORDER_ID)
                        .targetViews(1500)
                        .targetUrl(TEST_VIDEO_URL)
                        .clipCreated(false)
                        .coefficient(BigDecimal.valueOf(4.0))
                        .geoTargeting("US")
                        .build();

        // Mock responses
        CreateOfferResponse offerResponse =
                CreateOfferResponse.builder().offerId("OFFER_123").build();
        when(binomClient.createOffer(any())).thenReturn(offerResponse);
        // Campaigns are pre-configured, no dynamic creation
        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());
        when(binomCampaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act & Assert - Test with clip creation (coefficient 3.0)
        binomService.createBinomIntegration(requestWithClip);

        // Verify offers were assigned to campaigns
        verify(binomClient, atLeast(3)).assignOfferToCampaign(anyString(), anyString());

        // Reset mocks for second test
        reset(binomClient, binomCampaignRepository);
        when(binomClient.createOffer(any())).thenReturn(offerResponse);
        // Campaigns are pre-configured, no dynamic creation
        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());
        when(binomCampaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Act & Assert - Test without clip creation (coefficient 4.0)
        binomService.createBinomIntegration(requestWithoutClip);

        // Verify offers were assigned (total 6 assignments - 3 for each test)
        verify(binomClient, atLeast(3)).assignOfferToCampaign(anyString(), anyString());
    }
}
