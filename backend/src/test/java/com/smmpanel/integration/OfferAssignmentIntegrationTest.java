package com.smmpanel.integration;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.entity.FixedBinomCampaign;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.FixedBinomCampaignRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Integration тесты для API назначения офферов */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
@Transactional
class OfferAssignmentIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;

    @Autowired private OrderRepository orderRepository;

    @Autowired private FixedBinomCampaignRepository fixedCampaignRepository;

    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // Setup test data
        setupTestData();
    }

    private void setupTestData() {
        // Create fixed campaigns without traffic sources
        FixedBinomCampaign campaign1 =
                FixedBinomCampaign.builder()
                        .campaignId("TEST_CAMPAIGN_001")
                        .campaignName("Test Fixed Campaign 1")
                        .active(true)
                        .build();
        fixedCampaignRepository.save(campaign1);

        FixedBinomCampaign campaign2 =
                FixedBinomCampaign.builder()
                        .campaignId("TEST_CAMPAIGN_002")
                        .campaignName("Test Fixed Campaign 2")
                        .active(true)
                        .build();
        fixedCampaignRepository.save(campaign2);

        FixedBinomCampaign campaign3 =
                FixedBinomCampaign.builder()
                        .campaignId("TEST_CAMPAIGN_003")
                        .campaignName("Test Fixed Campaign 3")
                        .active(true)
                        .build();
        fixedCampaignRepository.save(campaign3);

        // Create test order
        testOrder =
                Order.builder()
                        .quantity(1000)
                        .link("https://youtube.com/watch?v=test123")
                        .status(OrderStatus.PENDING)
                        .build();
        testOrder = orderRepository.save(testOrder);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void testSyncOfferAssignment() throws Exception {
        // Given
        OfferAssignmentRequest request =
                OfferAssignmentRequest.builder()
                        .offerName("Integration Test Offer")
                        .targetUrl("https://youtube.com/watch?v=clip456")
                        .orderId(testOrder.getId())
                        .description("Integration test offer")
                        .geoTargeting("US")
                        .build();

        // When & Then
        mockMvc.perform(
                        post("/api/v2/binom/offers/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.orderId").value(testOrder.getId()))
                .andExpect(jsonPath("$.offerName").value("Integration Test Offer"))
                .andExpect(jsonPath("$.campaignsCreated").value(3))
                .andExpect(jsonPath("$.campaignIds").isArray())
                .andExpect(jsonPath("$.campaignIds.length()").value(3));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void testAsyncOfferAssignment() throws Exception {
        // Given
        OfferAssignmentRequest request =
                OfferAssignmentRequest.builder()
                        .offerName("Async Test Offer")
                        .targetUrl("https://youtube.com/watch?v=async789")
                        .orderId(testOrder.getId())
                        .build();

        // When & Then
        mockMvc.perform(
                        post("/api/v2/binom/offers/assign-async")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(
                        content()
                                .string(
                                        containsString(
                                                "Offer assignment event sent successfully")));
    }

    @Test
    @WithMockUser(roles = "USER")
    void testGetAssignedCampaigns() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v2/binom/offers/order/{orderId}/campaigns", testOrder.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void testQuickAssignOffer() throws Exception {
        // When & Then
        mockMvc.perform(
                        post("/api/v2/binom/offers/quick-assign")
                                .param("orderId", testOrder.getId().toString())
                                .param("targetUrl", "https://youtube.com/watch?v=quick123"))
                .andExpect(status().isOk())
                .andExpect(content().string("Assignment initiated"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void testUnauthorizedAccess() throws Exception {
        // Given
        OfferAssignmentRequest request =
                OfferAssignmentRequest.builder()
                        .offerName("Unauthorized Test")
                        .targetUrl("https://youtube.com/watch?v=test")
                        .orderId(testOrder.getId())
                        .build();

        // When & Then - USER role shouldn't be able to assign offers
        mockMvc.perform(
                        post("/api/v2/binom/offers/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void testValidationErrors() throws Exception {
        // Given - Invalid request (missing required fields)
        OfferAssignmentRequest invalidRequest =
                OfferAssignmentRequest.builder()
                        .offerName("") // Empty name
                        .orderId(null) // Null order ID
                        .build();

        // When & Then
        mockMvc.perform(
                        post("/api/v2/binom/offers/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void testNonExistentOrder() throws Exception {
        // Given
        OfferAssignmentRequest request =
                OfferAssignmentRequest.builder()
                        .offerName("Test Offer")
                        .targetUrl("https://youtube.com/watch?v=test")
                        .orderId(99999L) // Non-existent order
                        .build();

        // When & Then
        mockMvc.perform(
                        post("/api/v2/binom/offers/assign")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("Order not found")));
    }
}
