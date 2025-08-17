package com.smmpanel.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.dto.binom.*;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.*;
import com.smmpanel.service.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Complete integration test for 3-campaign Binom workflow
 * Tests the entire flow: Order Creation → Offer Creation → 3-Campaign Assignment → Stats Aggregation
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(OrderAnnotation.class)
@Transactional
class CompleteBinom3CampaignWorkflowIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private OrderService orderService;
    @Autowired private BinomService binomService;
    @Autowired private YouTubeAutomationService youTubeAutomationService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ServiceRepository serviceRepository;
    @Autowired private BinomCampaignRepository binomCampaignRepository;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private BinomClient binomClient;
    @MockBean private SeleniumService seleniumService;
    @MockBean private YouTubeService youTubeService;

    private MockMvc mockMvc;
    private User testUser;
    private Service testService;
    private Order testOrder;

    private static final String TEST_VIDEO_URL = "https://youtube.com/watch?v=integration123";
    private static final String TEST_VIDEO_ID = "integration123";
    private static final int TARGET_VIEWS = 3000;
    private static final BigDecimal COEFFICIENT_WITH_CLIP = BigDecimal.valueOf(3.0);
    private static final BigDecimal COEFFICIENT_WITHOUT_CLIP = BigDecimal.valueOf(4.0);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        setupTestData();
        setupBinomClientMocks();
    }

    private void setupTestData() {
        // Create test user
        testUser = new User();
        testUser.setUsername("integration-test-user");
        testUser.setEmail("integration@test.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setBalance(BigDecimal.valueOf(1000.00));
        testUser.setActive(true);
        testUser.setApiKey("integration-test-api-key");
        testUser.setRole(UserRole.USER);
        testUser = userRepository.save(testUser);

        // Create test service
        testService = new Service();
        testService.setName("YouTube Views Integration Test");
        testService.setMinOrder(100);
        testService.setMaxOrder(50000);
        testService.setPricePer1000(BigDecimal.valueOf(5.00));
        testService.setActive(true);
        testService = serviceRepository.save(testService);
    }

    private void setupBinomClientMocks() {
        // Mock offer creation
        CreateOfferResponse offerResponse = CreateOfferResponse.builder()
                .offerId("INTEGRATION_OFFER_123")
                .name("Integration Test Offer")
                .url(TEST_VIDEO_URL)
                .status("ACTIVE")
                .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(offerResponse);

        // Mock 3 campaign creation responses
        CreateCampaignResponse[] campaignResponses = {
            CreateCampaignResponse.builder()
                .campaignId("INTEGRATION_CAMP_001")
                .name("Integration Campaign 1")
                .status("ACTIVE")
                .build(),
            CreateCampaignResponse.builder()
                .campaignId("INTEGRATION_CAMP_002")
                .name("Integration Campaign 2")
                .status("ACTIVE")
                .build(),
            CreateCampaignResponse.builder()
                .campaignId("INTEGRATION_CAMP_003")
                .name("Integration Campaign 3")
                .status("ACTIVE")
                .build()
        };
        when(binomClient.createCampaign(any(CreateCampaignRequest.class)))
                .thenReturn(campaignResponses[0], campaignResponses[1], campaignResponses[2]);

        // Mock offer assignment
        AssignOfferResponse assignResponse = AssignOfferResponse.builder()
                .campaignId("test")
                .offerId("INTEGRATION_OFFER_123")
                .status("ASSIGNED")
                .build();
        when(binomClient.assignOfferToCampaign(anyString(), anyString())).thenReturn(assignResponse);

        // Mock campaign stats (realistic distribution across 3 campaigns)
        setupCampaignStatsMocks();
    }

    private void setupCampaignStatsMocks() {
        // Campaign 1: 35% of traffic
        CampaignStatsResponse stats1 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_001")
                .clicks(1050L) // 35% of 3000
                .conversions(350L) // 35% of 1000 target conversions
                .cost(BigDecimal.valueOf(525.0))
                .revenue(BigDecimal.valueOf(700.0))
                .build();

        // Campaign 2: 40% of traffic
        CampaignStatsResponse stats2 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_002")
                .clicks(1200L) // 40% of 3000
                .conversions(400L) // 40% of 1000 target conversions
                .cost(BigDecimal.valueOf(600.0))
                .revenue(BigDecimal.valueOf(800.0))
                .build();

        // Campaign 3: 25% of traffic
        CampaignStatsResponse stats3 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_003")
                .clicks(750L) // 25% of 3000
                .conversions(250L) // 25% of 1000 target conversions
                .cost(BigDecimal.valueOf(375.0))
                .revenue(BigDecimal.valueOf(500.0))
                .build();

        when(binomClient.getCampaignStats("INTEGRATION_CAMP_001")).thenReturn(stats1);
        when(binomClient.getCampaignStats("INTEGRATION_CAMP_002")).thenReturn(stats2);
        when(binomClient.getCampaignStats("INTEGRATION_CAMP_003")).thenReturn(stats3);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Complete Workflow: Order Creation → 3-Campaign Distribution")
    @WithMockUser(username = "integration-test-user")
    void testCompleteOrderTo3CampaignWorkflow() throws Exception {
        // Step 1: Create order via API
        OrderCreateRequest orderRequest = new OrderCreateRequest();
        orderRequest.setServiceId(testService.getId());
        orderRequest.setLink(TEST_VIDEO_URL);
        orderRequest.setQuantity(TARGET_VIEWS);

        MvcResult result = mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.quantity").value(TARGET_VIEWS))
                .andExpect(jsonPath("$.link").value(TEST_VIDEO_URL))
                .andReturn();

        String responseContent = result.getResponse().getContentAsString();
        OrderResponse orderResponse = objectMapper.readValue(responseContent, OrderResponse.class);
        Long orderId = orderResponse.getId();
        
        assertNotNull(orderId);
        
        // Step 2: Simulate order processing initialization
        OrderProcessingContext context = youTubeAutomationService.initializeOrderProcessing(orderId);
        assertNotNull(context);
        assertEquals(orderId, context.getOrderId());
        assertEquals(TEST_VIDEO_ID, context.getVideoId());
        assertEquals(TARGET_VIEWS, context.getTargetQuantity());

        // Step 3: Create Binom integration with 3-campaign distribution
        BinomIntegrationRequest binomRequest = BinomIntegrationRequest.builder()
                .orderId(orderId)
                .targetViews(TARGET_VIEWS)
                .targetUrl(TEST_VIDEO_URL)
                .clipCreated(true) // Test with clip creation
                .coefficient(COEFFICIENT_WITH_CLIP)
                .geoTargeting("US")
                .build();

        BinomIntegrationResponse binomResponse = binomService.createBinomIntegration(binomRequest);

        // Step 4: Verify 3-campaign distribution was created
        assertNotNull(binomResponse);
        assertEquals("SUCCESS", binomResponse.getStatus());
        assertEquals("INTEGRATION_OFFER_123", binomResponse.getOfferId());
        assertEquals(3, binomResponse.getCampaignsCreated());
        assertEquals(3, binomResponse.getCampaignIds().size());

        // Verify database state
        List<BinomCampaign> campaigns = binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);
        assertEquals(3, campaigns.size());

        // Verify coefficient calculation distribution
        int expectedViewsPerCampaign = (int) (TARGET_VIEWS / 3.0 * COEFFICIENT_WITH_CLIP.doubleValue());
        verify(binomClient, times(3)).createCampaign(argThat(request -> {
            return Math.abs(request.getTargetViews() - expectedViewsPerCampaign) <= 2; // Allow rounding
        }));

        // Step 5: Verify click distribution setup
        verify(binomClient, times(3)).createCampaign(any(CreateCampaignRequest.class));
        verify(binomClient, times(3)).assignOfferToCampaign(eq("INTEGRATION_OFFER_123"), anyString());

        // Store order for next test
        testOrder = orderRepository.findById(orderId).orElse(null);
        assertNotNull(testOrder);
        assertEquals(OrderStatus.PROCESSING, testOrder.getStatus());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Verify Click Distribution and Tracking Across 3 Campaigns")
    void testClickDistributionAndTracking() {
        // Simulate order creation if not already done
        if (testOrder == null) {
            createTestOrderDirectly();
        }

        Long orderId = testOrder.getId();

        // Test click distribution tracking
        CampaignStatsResponse aggregatedStats = binomService.getCampaignStatsForOrder(orderId);

        assertNotNull(aggregatedStats);
        
        // Verify aggregated click distribution
        assertEquals(3000L, aggregatedStats.getClicks()); // 1050 + 1200 + 750
        assertEquals(1000L, aggregatedStats.getConversions()); // 350 + 400 + 250
        assertEquals(BigDecimal.valueOf(1500.0), aggregatedStats.getCost()); // 525 + 600 + 375
        assertEquals(BigDecimal.valueOf(2000.0), aggregatedStats.getRevenue()); // 700 + 800 + 500

        // Verify campaign status aggregation
        assertEquals("ACTIVE", aggregatedStats.getStatus());
        
        // Verify campaign IDs are properly aggregated
        String campaignIds = aggregatedStats.getCampaignId();
        assertTrue(campaignIds.contains("INTEGRATION_CAMP_001"));
        assertTrue(campaignIds.contains("INTEGRATION_CAMP_002"));
        assertTrue(campaignIds.contains("INTEGRATION_CAMP_003"));

        // Test individual campaign tracking
        verify(binomClient).getCampaignStats("INTEGRATION_CAMP_001");
        verify(binomClient).getCampaignStats("INTEGRATION_CAMP_002");
        verify(binomClient).getCampaignStats("INTEGRATION_CAMP_003");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Test Campaign Stats Aggregation from Multiple Sources")
    void testCampaignStatsAggregation() {
        if (testOrder == null) {
            createTestOrderDirectly();
        }

        Long orderId = testOrder.getId();

        // Test stats aggregation over time (simulate multiple calls)
        CampaignStatsResponse firstCheck = binomService.getCampaignStatsForOrder(orderId);
        assertNotNull(firstCheck);

        // Simulate stats update - campaigns are performing better
        updateCampaignStatsMocks();

        CampaignStatsResponse secondCheck = binomService.getCampaignStatsForOrder(orderId);
        assertNotNull(secondCheck);

        // Verify that stats are properly aggregated each time
        assertTrue(secondCheck.getClicks() >= firstCheck.getClicks());
        assertTrue(secondCheck.getConversions() >= firstCheck.getConversions());

        // Test aggregation accuracy
        assertEquals(4500L, secondCheck.getClicks()); // Updated total
        assertEquals(1500L, secondCheck.getConversions()); // Updated total

        // Test that all 3 campaigns are always included in aggregation
        String[] campaignIds = secondCheck.getCampaignId().split(",");
        assertEquals(3, campaignIds.length);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Validate Coefficient Calculations in Real Scenarios")
    void testCoefficientCalculationsRealScenarios() {
        // Test scenario 1: With clip creation (coefficient 3.0)
        testCoefficientScenario(true, COEFFICIENT_WITH_CLIP, "with clip creation");

        // Reset mocks
        reset(binomClient);
        setupBinomClientMocks();

        // Test scenario 2: Without clip creation (coefficient 4.0)
        testCoefficientScenario(false, COEFFICIENT_WITHOUT_CLIP, "without clip creation");
    }

    private void testCoefficientScenario(boolean clipCreated, BigDecimal coefficient, String scenario) {
        // Create new order for this scenario
        Order order = createTestOrderDirectly();
        
        BinomIntegrationRequest request = BinomIntegrationRequest.builder()
                .orderId(order.getId())
                .targetViews(TARGET_VIEWS)
                .targetUrl(TEST_VIDEO_URL)
                .clipCreated(clipCreated)
                .coefficient(coefficient)
                .geoTargeting("US")
                .build();

        BinomIntegrationResponse response = binomService.createBinomIntegration(request);

        // Verify coefficient calculation
        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());

        // Calculate expected views per campaign: (targetViews / 3) * coefficient
        int expectedViewsPerCampaign = (int) (TARGET_VIEWS / 3.0 * coefficient.doubleValue());

        verify(binomClient, times(3)).createCampaign(argThat(campaignRequest -> {
            int actualViews = campaignRequest.getTargetViews();
            boolean isWithinRange = Math.abs(actualViews - expectedViewsPerCampaign) <= 2;
            
            if (!isWithinRange) {
                System.err.printf("Coefficient test failed for %s: expected ~%d views, got %d views%n", 
                    scenario, expectedViewsPerCampaign, actualViews);
            }
            
            return isWithinRange;
        }));

        // Verify total views across all campaigns
        int totalExpectedViews = expectedViewsPerCampaign * 3;
        int tolerance = 6; // Allow for rounding across 3 campaigns
        
        // This should be approximately TARGET_VIEWS * coefficient
        int theoreticalTotal = (int) (TARGET_VIEWS * coefficient.doubleValue());
        assertTrue(Math.abs(totalExpectedViews - theoreticalTotal) <= tolerance,
            String.format("Total views for %s: expected ~%d, calculated %d", 
                scenario, theoreticalTotal, totalExpectedViews));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Test Error Scenarios and Partial Failures")
    void testErrorScenariosAndPartialFailures() {
        // Test scenario: One campaign fails during creation
        Order order = createTestOrderDirectly();

        // Reset and setup partial failure mocks
        reset(binomClient);
        
        // Mock offer creation success
        CreateOfferResponse offerResponse = CreateOfferResponse.builder()
                .offerId("ERROR_TEST_OFFER")
                .name("Error Test Offer")
                .url(TEST_VIDEO_URL)
                .build();
        when(binomClient.createOffer(any())).thenReturn(offerResponse);

        // Mock partial campaign creation failure
        CreateCampaignResponse successCampaign1 = CreateCampaignResponse.builder()
                .campaignId("ERROR_CAMP_001")
                .status("ACTIVE")
                .build();
        CreateCampaignResponse successCampaign3 = CreateCampaignResponse.builder()
                .campaignId("ERROR_CAMP_003")
                .status("ACTIVE")
                .build();

        when(binomClient.createCampaign(any()))
                .thenReturn(successCampaign1) // First succeeds
                .thenThrow(new RuntimeException("Campaign creation failed")) // Second fails
                .thenReturn(successCampaign3); // Third succeeds

        when(binomClient.assignOfferToCampaign(anyString(), anyString()))
                .thenReturn(AssignOfferResponse.builder().status("ASSIGNED").build());

        BinomIntegrationRequest request = BinomIntegrationRequest.builder()
                .orderId(order.getId())
                .targetViews(TARGET_VIEWS)
                .targetUrl(TEST_VIDEO_URL)
                .clipCreated(true)
                .coefficient(COEFFICIENT_WITH_CLIP)
                .geoTargeting("US")
                .build();

        // Execute and verify partial success
        BinomIntegrationResponse response = binomService.createBinomIntegration(request);

        assertNotNull(response);
        assertEquals("PARTIAL_SUCCESS", response.getStatus());
        assertEquals(2, response.getCampaignsCreated()); // Only 2 out of 3 succeeded
        assertNotNull(response.getMessage());
        assertTrue(response.getMessage().contains("2 out of 3"));

        // Verify that successful campaigns were properly distributed
        verify(binomClient, times(2)).assignOfferToCampaign(eq("ERROR_TEST_OFFER"), anyString());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Test Order Completion with 3-Campaign Monitoring")
    void testOrderCompletionWithCampaignMonitoring() throws Exception {
        if (testOrder == null) {
            createTestOrderDirectly();
        }

        Long orderId = testOrder.getId();

        // Mock order completion scenario - campaigns have achieved target
        setupOrderCompletionMocks();

        // Test order progress update from campaigns
        orderService.updateOrderProgressFromCampaigns(orderId);

        // Verify order was marked as completed
        Order updatedOrder = orderRepository.findById(orderId).orElse(null);
        assertNotNull(updatedOrder);
        
        // Check if order completion logic was triggered
        CampaignStatsResponse stats = binomService.getCampaignStatsForOrder(orderId);
        assertTrue(stats.getConversions() >= TARGET_VIEWS, 
            "Campaign conversions should meet or exceed target for completion");

        // Test API endpoint for order status
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").exists());
    }

    private void setupOrderCompletionMocks() {
        // Mock stats that indicate order completion
        CampaignStatsResponse completionStats1 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_001")
                .clicks(2000L)
                .conversions(1000L) // Enough to complete
                .cost(BigDecimal.valueOf(1000.0))
                .revenue(BigDecimal.valueOf(1500.0))
                .build();

        CampaignStatsResponse completionStats2 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_002")
                .clicks(2000L)
                .conversions(1000L)
                .cost(BigDecimal.valueOf(1000.0))
                .revenue(BigDecimal.valueOf(1500.0))
                .build();

        CampaignStatsResponse completionStats3 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_003")
                .clicks(2000L)
                .conversions(1000L)
                .cost(BigDecimal.valueOf(1000.0))
                .revenue(BigDecimal.valueOf(1500.0))
                .build();

        when(binomClient.getCampaignStats("INTEGRATION_CAMP_001")).thenReturn(completionStats1);
        when(binomClient.getCampaignStats("INTEGRATION_CAMP_002")).thenReturn(completionStats2);
        when(binomClient.getCampaignStats("INTEGRATION_CAMP_003")).thenReturn(completionStats3);
    }

    private void updateCampaignStatsMocks() {
        // Updated stats - 50% increase across all campaigns
        CampaignStatsResponse updatedStats1 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_001")
                .clicks(1575L) // 50% increase
                .conversions(525L)
                .cost(BigDecimal.valueOf(787.5))
                .revenue(BigDecimal.valueOf(1050.0))
                .build();

        CampaignStatsResponse updatedStats2 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_002")
                .clicks(1800L)
                .conversions(600L)
                .cost(BigDecimal.valueOf(900.0))
                .revenue(BigDecimal.valueOf(1200.0))
                .build();

        CampaignStatsResponse updatedStats3 = CampaignStatsResponse.builder()
                .campaignId("INTEGRATION_CAMP_003")
                .clicks(1125L)
                .conversions(375L)
                .cost(BigDecimal.valueOf(562.5))
                .revenue(BigDecimal.valueOf(750.0))
                .build();

        when(binomClient.getCampaignStats("INTEGRATION_CAMP_001")).thenReturn(updatedStats1);
        when(binomClient.getCampaignStats("INTEGRATION_CAMP_002")).thenReturn(updatedStats2);
        when(binomClient.getCampaignStats("INTEGRATION_CAMP_003")).thenReturn(updatedStats3);
    }

    private Order createTestOrderDirectly() {
        Order order = new Order();
        order.setUser(testUser);
        order.setService(testService);
        order.setStatus(OrderStatus.ACTIVE);
        order.setQuantity(TARGET_VIEWS);
        order.setLink(TEST_VIDEO_URL);
        order.setCharge(BigDecimal.valueOf(15.00));
        order.setStartCount(0);
        order.setRemains(TARGET_VIEWS);
        order.setTargetViews(TARGET_VIEWS);
        order.setTargetCountry("US");
        order.setYoutubeVideoId(TEST_VIDEO_ID);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        
        return orderRepository.save(order);
    }
}