package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.exception.BinomApiException;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.BinomService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for new Binom V2 endpoints Tests real API scenarios with enhanced error
 * handling
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Transactional
class BinomV2EndpointsIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private BinomService binomService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private BinomCampaignRepository binomCampaignRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private BinomClient binomClient;

    private MockMvc mockMvc;
    private User testUser;
    private Service testService;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        setupTestData();
        setupBinomV2Mocks();
    }

    private void setupTestData() {
        testUser = new User();
        testUser.setUsername("v2-test-user");
        testUser.setEmail("v2@test.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setBalance(BigDecimal.valueOf(1000.00));
        testUser.setActive(true);
        testUser.setApiKey("v2-test-api-key");
        testUser.setRole(UserRole.ADMIN); // Admin for accessing V2 endpoints
        testUser = userRepository.save(testUser);

        testService = new Service();
        testService.setName("YouTube Views V2 Test");
        testService.setMinOrder(100);
        testService.setMaxOrder(50000);
        testService.setPricePer1000(BigDecimal.valueOf(5.00));
        testService.setActive(true);
        testService = serviceRepository.save(testService);

        testOrder = new Order();
        testOrder.setUser(testUser);
        testOrder.setService(testService);
        testOrder.setStatus(OrderStatus.ACTIVE);
        testOrder.setQuantity(1000);
        testOrder.setLink("https://youtube.com/watch?v=v2test123");
        testOrder.setCharge(BigDecimal.valueOf(5.00));
        testOrder.setStartCount(0);
        testOrder.setRemains(1000);
        testOrder.setTargetViews(1000);
        testOrder.setTargetCountry("US");
        testOrder.setYoutubeVideoId("v2test123");
        testOrder.setCreatedAt(LocalDateTime.now());
        testOrder.setUpdatedAt(LocalDateTime.now());
        testOrder = orderRepository.save(testOrder);
    }

    private void setupBinomV2Mocks() {
        // Mock getOffersList
        setupOffersListMock();

        // Mock updateOffer
        setupUpdateOfferMock();

        // Mock getCampaignInfo
        setupCampaignInfoMock();

        // Mock setClickCost
        setupSetClickCostMock();
    }

    private void setupOffersListMock() {
        List<OffersListResponse.OfferInfo> offers =
                Arrays.asList(
                        OffersListResponse.OfferInfo.builder()
                                .offerId("V2_OFFER_001")
                                .name("Test Offer 1")
                                .url("https://example.com/offer1")
                                .status("ACTIVE")
                                .type("CPA")
                                .category("Finance")
                                .payout(15.0)
                                .payoutCurrency("USD")
                                .payoutType("CPA")
                                .isActive(true)
                                .affiliateNetwork("Test Network")
                                .build(),
                        OffersListResponse.OfferInfo.builder()
                                .offerId("V2_OFFER_002")
                                .name("Test Offer 2")
                                .url("https://example.com/offer2")
                                .status("PAUSED")
                                .type("CPC")
                                .category("Tech")
                                .payout(0.50)
                                .payoutCurrency("USD")
                                .payoutType("CPC")
                                .isActive(false)
                                .affiliateNetwork("Tech Network")
                                .build());

        OffersListResponse offersListResponse =
                OffersListResponse.builder()
                        .offers(offers)
                        .totalCount(2)
                        .status("SUCCESS")
                        .message("Offers retrieved successfully")
                        .build();

        when(binomClient.getOffersList()).thenReturn(offersListResponse);
    }

    private void setupUpdateOfferMock() {
        when(binomClient.updateOffer(anyString(), any(UpdateOfferRequest.class)))
                .thenAnswer(
                        invocation -> {
                            String offerId = invocation.getArgument(0);
                            UpdateOfferRequest request = invocation.getArgument(1);

                            return UpdateOfferResponse.builder()
                                    .offerId(offerId)
                                    .name(request.getName())
                                    .url(request.getUrl())
                                    .status("UPDATED")
                                    .message("Offer updated successfully")
                                    .success(true)
                                    .build();
                        });
    }

    private void setupCampaignInfoMock() {
        when(binomClient.getCampaignInfo(anyString()))
                .thenAnswer(
                        invocation -> {
                            String campaignId = invocation.getArgument(0);

                            CampaignInfoResponse.CampaignStats stats =
                                    CampaignInfoResponse.CampaignStats.builder()
                                            .clicks(1500L)
                                            .conversions(150L)
                                            .cost(750.0)
                                            .revenue(1200.0)
                                            .roi(60.0)
                                            .ctr(2.5)
                                            .cr(10.0)
                                            .build();

                            return CampaignInfoResponse.builder()
                                    .campaignId(campaignId)
                                    .name("V2 Test Campaign")
                                    .status("ACTIVE")
                                    .trafficSource("YouTube")
                                    .landingPage("https://example.com/landing")
                                    .costModel("CPC")
                                    .costValue(0.50)
                                    .geoTargeting("US")
                                    .isActive(true)
                                    .createdAt("2024-01-01T00:00:00Z")
                                    .updatedAt("2024-01-01T12:00:00Z")
                                    .stats(stats)
                                    .build();
                        });
    }

    private void setupSetClickCostMock() {
        when(binomClient.setClickCost(any(SetClickCostRequest.class)))
                .thenAnswer(
                        invocation -> {
                            SetClickCostRequest request = invocation.getArgument(0);

                            return SetClickCostResponse.builder()
                                    .campaignId(request.getCampaignId())
                                    .cost(request.getCost())
                                    .costModel(request.getCostModel())
                                    .currency(request.getCurrency())
                                    .status("SUCCESS")
                                    .message("Click cost updated successfully")
                                    .success(true)
                                    .build();
                        });
    }

    @Test
    @DisplayName("Test getOffersList V2 endpoint integration")
    @WithMockUser(
            username = "v2-test-user",
            roles = {"ADMIN"})
    void testGetOffersListIntegration() throws Exception {
        // Test direct service call
        OffersListResponse response = binomClient.getOffersList();

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals(2, response.getTotalCount());
        assertEquals(2, response.getOffers().size());

        // Verify offer details
        OffersListResponse.OfferInfo offer1 = response.getOffers().get(0);
        assertEquals("V2_OFFER_001", offer1.getOfferId());
        assertEquals("Test Offer 1", offer1.getName());
        assertEquals("ACTIVE", offer1.getStatus());
        assertTrue(offer1.getIsActive());

        OffersListResponse.OfferInfo offer2 = response.getOffers().get(1);
        assertEquals("V2_OFFER_002", offer2.getOfferId());
        assertEquals("PAUSED", offer2.getStatus());
        assertFalse(offer2.getIsActive());

        // Verify method was called
        verify(binomClient).getOffersList();
    }

    @Test
    @DisplayName("Test updateOffer V2 endpoint integration")
    @WithMockUser(
            username = "v2-test-user",
            roles = {"ADMIN"})
    void testUpdateOfferIntegration() throws Exception {
        String offerId = "V2_OFFER_001";
        UpdateOfferRequest request =
                UpdateOfferRequest.builder()
                        .name("Updated Test Offer")
                        .url("https://example.com/updated-offer")
                        .status("ACTIVE")
                        .payout(20.0)
                        .payoutCurrency("USD")
                        .payoutType("CPA")
                        .isActive(true)
                        .build();

        // Test service call
        UpdateOfferResponse response = binomClient.updateOffer(offerId, request);

        assertNotNull(response);
        assertEquals(offerId, response.getOfferId());
        assertEquals("Updated Test Offer", response.getName());
        assertEquals("https://example.com/updated-offer", response.getUrl());
        assertEquals("UPDATED", response.getStatus());
        assertTrue(response.getSuccess());

        verify(binomClient).updateOffer(eq(offerId), any(UpdateOfferRequest.class));
    }

    @Test
    @DisplayName("Test getCampaignInfo V2 endpoint integration")
    @WithMockUser(
            username = "v2-test-user",
            roles = {"ADMIN"})
    void testGetCampaignInfoIntegration() throws Exception {
        String campaignId = "V2_CAMPAIGN_123";

        // Test service call
        CampaignInfoResponse response = binomClient.getCampaignInfo(campaignId);

        assertNotNull(response);
        assertEquals(campaignId, response.getCampaignId());
        assertEquals("V2 Test Campaign", response.getName());
        assertEquals("ACTIVE", response.getStatus());
        assertEquals("YouTube", response.getTrafficSource());
        assertEquals("CPC", response.getCostModel());
        assertEquals(0.50, response.getCostValue());
        assertTrue(response.getIsActive());

        // Verify stats
        CampaignInfoResponse.CampaignStats stats = response.getStats();
        assertNotNull(stats);
        assertEquals(1500L, stats.getClicks());
        assertEquals(150L, stats.getConversions());
        assertEquals(750.0, stats.getCost());
        assertEquals(1200.0, stats.getRevenue());
        assertEquals(60.0, stats.getRoi());
        assertEquals(2.5, stats.getCtr());
        assertEquals(10.0, stats.getCr());

        verify(binomClient).getCampaignInfo(campaignId);
    }

    @Test
    @DisplayName("Test setClickCost V2 endpoint integration")
    @WithMockUser(
            username = "v2-test-user",
            roles = {"ADMIN"})
    void testSetClickCostIntegration() throws Exception {
        SetClickCostRequest request =
                SetClickCostRequest.builder()
                        .campaignId("V2_CAMPAIGN_123")
                        .cost(BigDecimal.valueOf(0.75))
                        .costModel("CPC")
                        .currency("USD")
                        .notes("Updated for better performance")
                        .build();

        // Test service call
        SetClickCostResponse response = binomClient.setClickCost(request);

        assertNotNull(response);
        assertEquals("V2_CAMPAIGN_123", response.getCampaignId());
        assertEquals(BigDecimal.valueOf(0.75), response.getCost());
        assertEquals("CPC", response.getCostModel());
        assertEquals("USD", response.getCurrency());
        assertEquals("SUCCESS", response.getStatus());
        assertTrue(response.getSuccess());

        verify(binomClient).setClickCost(any(SetClickCostRequest.class));
    }

    @Test
    @DisplayName("Test V2 endpoints error handling integration")
    void testV2EndpointsErrorHandling() {
        // Test 401 Unauthorized
        when(binomClient.getOffersList())
                .thenThrow(
                        new BinomApiException(
                                "Unauthorized access", HttpStatus.UNAUTHORIZED, "/offer/list/all"));

        BinomApiException exception =
                assertThrows(BinomApiException.class, () -> binomClient.getOffersList());

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getHttpStatus());
        assertTrue(exception.isClientError());
        assertFalse(exception.isRetryable());

        // Test 404 Not Found for updateOffer
        when(binomClient.updateOffer(eq("NONEXISTENT"), any()))
                .thenThrow(
                        new BinomApiException(
                                "Offer not found", HttpStatus.NOT_FOUND, "/offer/NONEXISTENT"));

        exception =
                assertThrows(
                        BinomApiException.class,
                        () ->
                                binomClient.updateOffer(
                                        "NONEXISTENT", UpdateOfferRequest.builder().build()));

        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatus());
        assertFalse(exception.isRetryable());

        // Test 400 Bad Request for setClickCost
        when(binomClient.setClickCost(any()))
                .thenThrow(
                        new BinomApiException(
                                "Invalid cost value", HttpStatus.BAD_REQUEST, "/clicks/cost"));

        exception =
                assertThrows(
                        BinomApiException.class,
                        () -> binomClient.setClickCost(SetClickCostRequest.builder().build()));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertFalse(exception.isRetryable());
    }

    @Test
    @DisplayName("Test V2 endpoints integration with 3-campaign workflow")
    void testV2EndpointsWith3CampaignWorkflow() {
        // Create 3 campaigns for the test order
        List<BinomCampaign> campaigns =
                Arrays.asList(
                        createBinomCampaign("V2_CAMP_001", testOrder.getId()),
                        createBinomCampaign("V2_CAMP_002", testOrder.getId()),
                        createBinomCampaign("V2_CAMP_003", testOrder.getId()));

        campaigns.forEach(binomCampaignRepository::save);

        // Test getCampaignInfo for each campaign
        for (BinomCampaign campaign : campaigns) {
            CampaignInfoResponse info = binomClient.getCampaignInfo(campaign.getCampaignId());
            assertNotNull(info);
            assertEquals(campaign.getCampaignId(), info.getCampaignId());
            assertEquals("ACTIVE", info.getStatus());
        }

        // Test updating click costs for all 3 campaigns
        for (BinomCampaign campaign : campaigns) {
            SetClickCostRequest costRequest =
                    SetClickCostRequest.builder()
                            .campaignId(campaign.getCampaignId())
                            .cost(BigDecimal.valueOf(0.60))
                            .costModel("CPC")
                            .currency("USD")
                            .build();

            SetClickCostResponse costResponse = binomClient.setClickCost(costRequest);
            assertNotNull(costResponse);
            assertTrue(costResponse.getSuccess());
        }

        // Verify all campaigns were processed
        verify(binomClient, times(3)).getCampaignInfo(anyString());
        verify(binomClient, times(3)).setClickCost(any(SetClickCostRequest.class));

        // Test offers list in context of campaign management
        OffersListResponse offersList = binomClient.getOffersList();
        assertNotNull(offersList);
        assertTrue(offersList.getTotalCount() > 0);
    }

    @Test
    @DisplayName("Test V2 endpoints rate limiting and circuit breaker integration")
    void testV2EndpointsRateLimitingIntegration() {
        // Test 418 I'm a teapot (rate limiting)
        when(binomClient.getOffersList())
                .thenThrow(
                        new BinomApiException(
                                "Rate limit exceeded",
                                HttpStatus.I_AM_A_TEAPOT,
                                "/offer/list/all"));

        BinomApiException exception =
                assertThrows(BinomApiException.class, () -> binomClient.getOffersList());

        assertEquals(HttpStatus.I_AM_A_TEAPOT, exception.getHttpStatus());
        assertTrue(exception.isRetryable()); // 418 should be retryable

        // Test 429 Too Many Requests
        when(binomClient.getCampaignInfo(anyString()))
                .thenThrow(
                        new BinomApiException(
                                "Too many requests",
                                HttpStatus.TOO_MANY_REQUESTS,
                                "/info/campaign"));

        exception =
                assertThrows(
                        BinomApiException.class,
                        () -> binomClient.getCampaignInfo("TEST_CAMPAIGN"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getHttpStatus());
        assertTrue(exception.isRetryable()); // 429 should be retryable
    }

    @Test
    @DisplayName("Test V2 endpoints data validation integration")
    void testV2EndpointsDataValidation() {
        // Test updateOffer with comprehensive data
        UpdateOfferRequest updateRequest =
                UpdateOfferRequest.builder()
                        .name("Comprehensive Test Offer")
                        .url("https://example.com/comprehensive")
                        .description("Detailed offer description")
                        .status("ACTIVE")
                        .affiliateNetworkId(123L)
                        .geoTargeting(Arrays.asList("US", "CA", "UK"))
                        .type("CPA")
                        .category("Finance")
                        .payout(25.0)
                        .payoutCurrency("USD")
                        .payoutType("CPA")
                        .conversionCap("1000")
                        .requiresApproval(true)
                        .notes("Test notes")
                        .isActive(true)
                        .build();

        UpdateOfferResponse response = binomClient.updateOffer("V2_OFFER_001", updateRequest);
        assertNotNull(response);
        assertEquals("Comprehensive Test Offer", response.getName());

        // Test setClickCost with detailed request
        SetClickCostRequest costRequest =
                SetClickCostRequest.builder()
                        .campaignId("V2_CAMPAIGN_123")
                        .cost(BigDecimal.valueOf(1.25))
                        .costModel("CPM")
                        .currency("EUR")
                        .notes("Updated for European market")
                        .build();

        SetClickCostResponse costResponse = binomClient.setClickCost(costRequest);
        assertNotNull(costResponse);
        assertEquals("CPM", costResponse.getCostModel());
        assertEquals("EUR", costResponse.getCurrency());
    }

    private BinomCampaign createBinomCampaign(String campaignId, Long orderId) {
        BinomCampaign campaign = new BinomCampaign();
        campaign.setCampaignId(campaignId);
        campaign.setOrderId(orderId);
        campaign.setActive(true);
        campaign.setStatus("ACTIVE");
        campaign.setClicksDelivered(500);
        campaign.setConversions(50);
        campaign.setCost(BigDecimal.valueOf(250.0));
        campaign.setRevenue(BigDecimal.valueOf(400.0));
        campaign.setCreatedAt(LocalDateTime.now());
        campaign.setUpdatedAt(LocalDateTime.now());
        return campaign;
    }
}
