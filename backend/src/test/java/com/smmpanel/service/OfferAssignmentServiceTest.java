package com.smmpanel.service;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OfferAssignmentServiceTest {

    @Mock
    private BinomClient binomClient;
    
    @Mock
    private FixedBinomCampaignRepository fixedCampaignRepository;
    
    @Mock
    private BinomCampaignRepository campaignRepository;
    
    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private OperatorLogRepository operatorLogRepository;
    
    @Mock
    private ConversionCoefficientRepository coefficientRepository;

    @InjectMocks
    private OfferAssignmentService offerAssignmentService;

    private Order testOrder;
    private List<FixedBinomCampaign> fixedCampaigns;
    private OfferAssignmentRequest request;

    @BeforeEach
    void setUp() {
        // Setup test order
        testOrder = Order.builder()
                .id(1L)
                .quantity(1000)
                .link("https://youtube.com/watch?v=test123")
                .build();

        // Setup fixed campaigns
        TrafficSource source1 = TrafficSource.builder().id(1L).name("Source 1").build();
        TrafficSource source2 = TrafficSource.builder().id(2L).name("Source 2").build();
        TrafficSource source3 = TrafficSource.builder().id(3L).name("Source 3").build();

        fixedCampaigns = Arrays.asList(
                FixedBinomCampaign.builder()
                        .id(1L)
                        .campaignId("CAMPAIGN_001")
                        .trafficSource(source1)
                        .active(true)
                        .build(),
                FixedBinomCampaign.builder()
                        .id(2L)
                        .campaignId("CAMPAIGN_002")
                        .trafficSource(source2)
                        .active(true)
                        .build(),
                FixedBinomCampaign.builder()
                        .id(3L)
                        .campaignId("CAMPAIGN_003")
                        .trafficSource(source3)
                        .active(true)
                        .build()
        );

        // Setup request
        request = OfferAssignmentRequest.builder()
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
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(fixedCampaignRepository.findAllActiveCampaigns()).thenReturn(fixedCampaigns);
        
        // Mock offer creation
        CheckOfferResponse checkResponse = CheckOfferResponse.builder()
                .exists(false)
                .build();
        when(binomClient.checkOfferExists("Test Offer")).thenReturn(checkResponse);
        
        CreateOfferResponse createResponse = CreateOfferResponse.builder()
                .offerId("OFFER_123")
                .name("Test Offer")
                .status("SUCCESS")
                .build();
        when(binomClient.createOffer(any(CreateOfferRequest.class))).thenReturn(createResponse);

        // Mock campaign assignments
        AssignOfferResponse assignResponse = AssignOfferResponse.builder()
                .campaignId("CAMPAIGN_001")
                .offerId("OFFER_123")
                .status("SUCCESS")
                .build();
        when(binomClient.assignOfferToCampaign(anyString(), eq("OFFER_123"))).thenReturn(assignResponse);

        // Mock repository saves
        when(campaignRepository.save(any(BinomCampaign.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operatorLogRepository.save(any(OperatorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("OFFER_123", response.getOfferId());
        assertEquals(3, response.getCampaignsCreated());
        assertEquals(3, response.getCampaignIds().size());
        assertTrue(response.getCampaignIds().contains("CAMPAIGN_001"));
        assertTrue(response.getCampaignIds().contains("CAMPAIGN_002"));
        assertTrue(response.getCampaignIds().contains("CAMPAIGN_003"));

        // Verify interactions
        verify(binomClient, times(1)).createOffer(any(CreateOfferRequest.class));
        verify(binomClient, times(3)).assignOfferToCampaign(anyString(), eq("OFFER_123"));
        verify(campaignRepository, times(3)).save(any(BinomCampaign.class));
        verify(operatorLogRepository, times(1)).save(any(OperatorLog.class));
    }

    @Test
    void testOfferAssignmentWithExistingOffer() {
        // Given
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(fixedCampaignRepository.findAllActiveCampaigns()).thenReturn(fixedCampaigns);
        
        // Mock existing offer
        CheckOfferResponse checkResponse = CheckOfferResponse.builder()
                .exists(true)
                .offerId("EXISTING_OFFER_456")
                .name("Test Offer")
                .build();
        when(binomClient.checkOfferExists("Test Offer")).thenReturn(checkResponse);

        // Mock campaign assignments
        AssignOfferResponse assignResponse = AssignOfferResponse.builder()
                .status("SUCCESS")
                .build();
        when(binomClient.assignOfferToCampaign(anyString(), eq("EXISTING_OFFER_456"))).thenReturn(assignResponse);

        when(campaignRepository.save(any(BinomCampaign.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operatorLogRepository.save(any(OperatorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertEquals("SUCCESS", response.getStatus());
        assertEquals("EXISTING_OFFER_456", response.getOfferId());
        
        // Verify that createOffer was NOT called
        verify(binomClient, never()).createOffer(any(CreateOfferRequest.class));
        verify(binomClient, times(3)).assignOfferToCampaign(anyString(), eq("EXISTING_OFFER_456"));
    }

    @Test
    void testOfferAssignmentWithInvalidOrder() {
        // Given
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());
        
        OfferAssignmentRequest invalidRequest = OfferAssignmentRequest.builder()
                .offerName("Test Offer")
                .targetUrl("https://youtube.com/watch?v=test")
                .orderId(999L)
                .build();

        // When
        OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(invalidRequest);

        // Then
        assertEquals("ERROR", response.getStatus());
        assertNotNull(response.getMessage());
        assertTrue(response.getMessage().contains("Order not found"));
    }

    @Test
    void testOfferAssignmentWithIncorrectCampaignCount() {
        // Given
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        
        // Return only 2 campaigns instead of expected 3
        List<FixedBinomCampaign> incompleteCampaigns = fixedCampaigns.subList(0, 2);
        when(fixedCampaignRepository.findAllActiveCampaigns()).thenReturn(incompleteCampaigns);

        // When
        OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertEquals("ERROR", response.getStatus());
        assertTrue(response.getMessage().contains("Expected exactly 3 active fixed campaigns"));
    }

    @Test
    void testOfferAssignmentWithBinomApiError() {
        // Given
        when(orderRepository.findById(1L)).thenReturn(Optional.of(testOrder));
        when(fixedCampaignRepository.findAllActiveCampaigns()).thenReturn(fixedCampaigns);
        
        CheckOfferResponse checkResponse = CheckOfferResponse.builder().exists(false).build();
        when(binomClient.checkOfferExists("Test Offer")).thenReturn(checkResponse);
        
        // Mock Binom API error
        when(binomClient.createOffer(any(CreateOfferRequest.class)))
                .thenThrow(new RuntimeException("Binom API connection failed"));

        // When
        OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertEquals("ERROR", response.getStatus());
        assertTrue(response.getMessage().contains("Failed to assign offer"));
    }

    @Test
    void testGetAssignedCampaigns() {
        // Given
        List<BinomCampaign> campaigns = Arrays.asList(
                BinomCampaign.builder()
                        .campaignId("CAMPAIGN_001")
                        .offerId("OFFER_123")
                        .trafficSource(fixedCampaigns.get(0).getTrafficSource())
                        .clicksRequired(3000)
                        .status("ACTIVE")
                        .build(),
                BinomCampaign.builder()
                        .campaignId("CAMPAIGN_002")
                        .offerId("OFFER_123")
                        .trafficSource(fixedCampaigns.get(1).getTrafficSource())
                        .clicksRequired(3000)
                        .status("ACTIVE")
                        .build()
        );
        
        when(campaignRepository.findByOrderId(1L)).thenReturn(campaigns);

        // When
        List<AssignedCampaignInfo> result = offerAssignmentService.getAssignedCampaigns(1L);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("CAMPAIGN_001", result.get(0).getCampaignId());
        assertEquals("OFFER_123", result.get(0).getOfferId());
        assertEquals(3000, result.get(0).getClicksRequired());
    }

    @Test
    void testClicksCalculation() {
        // Given
        Order orderWith1000Views = Order.builder()
                .id(1L)
                .quantity(1000)
                .build();

        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderWith1000Views));
        when(fixedCampaignRepository.findAllActiveCampaigns()).thenReturn(fixedCampaigns);
        
        CheckOfferResponse checkResponse = CheckOfferResponse.builder()
                .exists(true)
                .offerId("TEST_OFFER")
                .build();
        when(binomClient.checkOfferExists(anyString())).thenReturn(checkResponse);

        AssignOfferResponse assignResponse = AssignOfferResponse.builder()
                .status("SUCCESS")
                .build();
        when(binomClient.assignOfferToCampaign(anyString(), anyString())).thenReturn(assignResponse);

        // Capture the saved campaigns to verify clicks calculation
        when(campaignRepository.save(any(BinomCampaign.class))).thenAnswer(invocation -> {
            BinomCampaign campaign = invocation.getArgument(0);
            // Verify that clicks required = quantity * coefficient (3.0)
            assertEquals(3000, campaign.getClicksRequired()); // 1000 * 3.0
            return campaign;
        });

        when(operatorLogRepository.save(any(OperatorLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);

        // Then
        assertEquals("SUCCESS", response.getStatus());
        verify(campaignRepository, times(3)).save(any(BinomCampaign.class));
    }
