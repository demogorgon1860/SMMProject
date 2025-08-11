package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.jpa.*;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OfferAssignmentServiceTest {

    @Mock private BinomService binomService;

    @Mock private FixedBinomCampaignRepository fixedCampaignRepository;

    @Mock private OrderRepository orderRepository;

    @InjectMocks private OfferAssignmentService offerAssignmentService;

    private List<FixedBinomCampaign> fixedCampaigns;
    private OfferAssignmentRequest request;

    @BeforeEach
    void setUp() {
        // Setup fixed campaigns
        fixedCampaigns =
                Arrays.asList(
                        FixedBinomCampaign.builder()
                                .id(1L)
                                .campaignId("CAMPAIGN_001")
                                .campaignName("Campaign 1")
                                .active(true)
                                .build(),
                        FixedBinomCampaign.builder()
                                .id(2L)
                                .campaignId("CAMPAIGN_002")
                                .campaignName("Campaign 2")
                                .active(true)
                                .build());

        // Setup request
        request =
                OfferAssignmentRequest.builder()
                        .offerName("Test Offer")
                        .targetUrl("https://youtube.com/watch?v=clip123")
                        .orderId(1L)
                        .description("Test offer description")
                        .geoTargeting("US")
                        .build();
    }

    @Test
    void testSuccessfulOfferAssignment() {
        // Given
        when(orderRepository.existsById(1L)).thenReturn(true);
        when(fixedCampaignRepository.findByActiveTrue()).thenReturn(fixedCampaigns);
        when(binomService.createOffer(
                        "Test Offer",
                        "https://youtube.com/watch?v=clip123",
                        "Test offer description"))
                .thenReturn("OFFER_123");
        when(binomService.assignOfferToCampaign("OFFER_123", anyString(), eq(1))).thenReturn(true);

        // When
        OfferAssignmentResponse response =
                offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("OFFER_123", response.getOfferId());
        assertEquals(2, response.getCampaignsCreated());
        assertEquals(2, response.getCampaignIds().size());
        assertTrue(response.getCampaignIds().contains("CAMPAIGN_001"));
        assertTrue(response.getCampaignIds().contains("CAMPAIGN_002"));

        // Verify interactions
        verify(binomService, times(1))
                .createOffer(
                        "Test Offer",
                        "https://youtube.com/watch?v=clip123",
                        "Test offer description");
        verify(binomService, times(2)).assignOfferToCampaign(eq("OFFER_123"), anyString(), eq(1));
    }

    @Test
    void testOfferAssignmentWithInvalidOrder() {
        // Given
        when(orderRepository.existsById(999L)).thenReturn(false);

        OfferAssignmentRequest invalidRequest =
                OfferAssignmentRequest.builder()
                        .offerName("Test Offer")
                        .targetUrl("https://youtube.com/watch?v=test")
                        .orderId(999L)
                        .build();

        // When
        OfferAssignmentResponse response =
                offerAssignmentService.assignOfferToFixedCampaigns(invalidRequest);

        // Then
        assertEquals("ERROR", response.getStatus());
        assertNotNull(response.getMessage());
        assertTrue(response.getMessage().contains("Invalid assignment request"));
    }

    @Test
    void testOfferAssignmentWithNoActiveCampaigns() {
        // Given
        when(orderRepository.existsById(1L)).thenReturn(true);
        when(fixedCampaignRepository.findByActiveTrue()).thenReturn(Arrays.asList());

        // When
        OfferAssignmentResponse response =
                offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertEquals("ERROR", response.getStatus());
        assertTrue(response.getMessage().contains("No active campaigns available"));
    }

    @Test
    void testGetAssignedCampaigns() {
        // Given
        when(orderRepository.findById(1L)).thenReturn(Optional.of(new Order()));
        when(fixedCampaignRepository.findByActiveTrue()).thenReturn(fixedCampaigns);

        // When
        List<AssignedCampaignInfo> result = offerAssignmentService.getAssignedCampaigns(1L);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("CAMPAIGN_001", result.get(0).getCampaignId());
        assertEquals("Campaign 1", result.get(0).getCampaignName());
    }

    @Test
    void testValidationMethods() {
        // Test valid request
        assertTrue(offerAssignmentService.validateAssignment(request));

        // Test null request
        assertFalse(offerAssignmentService.validateAssignment(null));

        // Test empty offer name
        OfferAssignmentRequest invalidRequest =
                OfferAssignmentRequest.builder()
                        .offerName("")
                        .targetUrl("https://youtube.com/watch?v=test")
                        .orderId(1L)
                        .build();
        assertFalse(offerAssignmentService.validateAssignment(invalidRequest));
    }

    @Test
    void testAssignmentStatusManagement() {
        // Test status update
        offerAssignmentService.updateAssignmentStatus(1L, "SUCCESS");
        assertEquals("SUCCESS", offerAssignmentService.getAssignmentStatus(1L));

        // Test default status for unknown order
        assertEquals("PENDING", offerAssignmentService.getAssignmentStatus(999L));
    }
}
