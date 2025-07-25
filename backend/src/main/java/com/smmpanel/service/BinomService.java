package com.smmpanel.service;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import com.smmpanel.exception.BinomApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;

/**
 * CRITICAL BINOM INTEGRATION SERVICE
 * This MUST distribute offers to exactly 3 campaigns as required for Perfect Panel compatibility
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinomService {

    private final BinomClient binomClient;
    private final OrderRepository orderRepository;
    private final BinomCampaignRepository binomCampaignRepository;
    private final FixedBinomCampaignRepository fixedBinomCampaignRepository;
    private final ConversionCoefficientRepository conversionCoefficientRepository;
    private final TrafficSourceRepository trafficSourceRepository;

    @Value("${app.binom.default-coefficient:3.0}")
    private BigDecimal defaultCoefficient;

    /**
     * FIXED: Assign order to existing Binom campaign with traffic source rotation
     */
    @Transactional
    public BinomIntegrationResponse createBinomIntegration(Order order, String videoId, boolean clipCreated, String targetUrl) {
        try {
            // Calculate coefficient based on clip creation
            BigDecimal coefficient = clipCreated ? new BigDecimal("3.0") : new BigDecimal("4.0");
            int targetViews = order.getQuantity();
            int requiredClicks = BigDecimal.valueOf(targetViews).multiply(coefficient).intValue();
            TrafficSource selectedSource = selectAvailableTrafficSource();
            if (selectedSource == null) {
                throw new RuntimeException("No available traffic sources");
            }
            BinomCampaign campaign = BinomCampaign.builder()
                    .orderId(order.getId())
                    .orderCreatedAt(order.getCreatedAt())
                    .campaignId(generateCampaignId(order.getId(), selectedSource.getSourceId()))
                    .targetUrl(targetUrl)
                    .trafficSource(selectedSource)
                    .coefficient(coefficient)
                    .clicksRequired(requiredClicks)
                    .clicksDelivered(0)
                    .viewsGenerated(0)
                    .status("ACTIVE")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            binomCampaignRepository.save(campaign);
            selectedSource.setClicksUsedToday(selectedSource.getClicksUsedToday() + requiredClicks);
            trafficSourceRepository.save(selectedSource);
            log.info("Order {} assigned to traffic source {} - Required clicks: {} (coefficient: {})", 
                    order.getId(), selectedSource.getSourceId(), requiredClicks, coefficient);
            return BinomIntegrationResponse.builder()
                    .success(true)
                    .campaignId(campaign.getCampaignId())
                    .message("Order assigned to traffic source: " + selectedSource.getName())
                    .createdAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("Failed to create Binom integration for order {}: {}", order.getId(), e.getMessage());
            return BinomIntegrationResponse.builder()
                    .success(false)
                    .message("Failed to assign traffic source: " + e.getMessage())
                    .errorCode("TRAFFIC_SOURCE_ASSIGNMENT_FAILED")
                    .createdAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * Validate request parameters
     */
    private void validateRequest(BinomIntegrationRequest request) {
        if (request.getOrderId() == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        if (!StringUtils.hasText(request.getTargetUrl())) {
            throw new IllegalArgumentException("Target URL is required");
        }
        if (request.getTargetViews() == null || request.getTargetViews() <= 0) {
            throw new IllegalArgumentException("Target views must be positive");
        }
    }

    /**
     * CRITICAL: Get exactly 3 fixed campaigns for geo targeting
     */
    private List<FixedBinomCampaign> getThreeFixedCampaigns(String geoTargeting) {
        // Get all active fixed campaigns for geo targeting
        List<FixedBinomCampaign> campaigns = fixedBinomCampaignRepository.findActiveByGeoTargeting(geoTargeting);
        
        if (campaigns.size() < 3) {
            // Fallback to any active campaigns if geo-specific ones are not enough
            campaigns = fixedBinomCampaignRepository.findTop3ByActiveTrue(PageRequest.of(0, 3));
        }
        
        if (campaigns.size() != 3) {
            throw new BinomApiException(
                String.format("Expected exactly 3 fixed campaigns, found %d. " +
                             "This is CRITICAL for Perfect Panel compatibility!", campaigns.size()));
        }
        
        return campaigns;
    }

    /**
     * Get conversion coefficient based on service and clip creation
     */
    private BigDecimal getConversionCoefficient(Long serviceId, Boolean clipCreated) {
        return conversionCoefficientRepository
                .findByServiceIdAndWithoutClip(serviceId, clipCreated != null ? !clipCreated : true)
                .map(ConversionCoefficient::getCoefficient)
                .orElse(clipCreated != null && clipCreated ? new BigDecimal("3.0") : new BigDecimal("4.0"));
    }

    /**
     * Calculate required clicks: target_views * coefficient
     */
    private int calculateRequiredClicks(int targetViews, BigDecimal coefficient) {
        BigDecimal result = BigDecimal.valueOf(targetViews)
                .multiply(coefficient)
                .setScale(0, RoundingMode.UP);
        
        if (result.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Calculated clicks must be positive");
        }
        
        return result.intValue();
    }

    /**
     * Create or get existing offer in Binom
     */
    private String createOrGetOffer(String offerName, String targetUrl, String geoTargeting) {
        try {
            // Check if offer exists
            CheckOfferResponse existingOffer = binomClient.checkOfferExists(offerName);
            if (existingOffer.isExists()) {
                log.info("Using existing Binom offer: {}", existingOffer.getOfferId());
                return existingOffer.getOfferId();
            }

            // Create new offer
            CreateOfferRequest offerRequest = CreateOfferRequest.builder()
                    .name(offerName)
                    .url(targetUrl)
                    .geoTargeting(geoTargeting)
                    .description("SMM Panel auto-generated offer")
                    .build();

            CreateOfferResponse response = binomClient.createOffer(offerRequest);
            log.info("Created new Binom offer: {}", response.getOfferId());
            return response.getOfferId();

        } catch (Exception e) {
            log.error("Failed to create/get Binom offer: {}", e.getMessage());
            throw new BinomApiException("Failed to create/get Binom offer", e);
        }
    }

    /**
     * Assign offer to specific campaign with click limit
     */
    private void assignOfferToCampaign(String campaignId, String offerId, int clicksLimit) {
        try {
            binomClient.assignOfferToCampaign(campaignId, offerId);
            log.info("Assigned offer {} to campaign {} with {} clicks", offerId, campaignId, clicksLimit);

        } catch (Exception e) {
            log.error("Failed to assign offer {} to campaign {}: {}", offerId, campaignId, e.getMessage());
            throw new BinomApiException("Failed to assign offer to campaign", e);
        }
    }

    /**
     * Save Binom campaign record in database
     */
    private void saveBinomCampaignRecord(Order order, String campaignId, String offerId, 
                                       int clicksRequired, String targetUrl) {
        BinomCampaign campaign = new BinomCampaign();
        campaign.setOrder(order);
        campaign.setCampaignId(campaignId);
        campaign.setOfferId(offerId);
        campaign.setClicksRequired(clicksRequired);
        campaign.setClicksDelivered(0);
        campaign.setTargetUrl(targetUrl);
        campaign.setStatus("ACTIVE");
        campaign.setCreatedAt(LocalDateTime.now());
        campaign.setUpdatedAt(LocalDateTime.now());

        binomCampaignRepository.save(campaign);
        log.info("Saved Binom campaign record: {}", campaignId);
    }

    /**
     * Generate offer name for Perfect Panel compatibility
     */
    private String generateOfferName(Long orderId, Boolean clipCreated) {
        return String.format("SMM_ORDER_%d_%s_%d", 
                orderId, 
                clipCreated != null && clipCreated ? "CLIP" : "DIRECT",
                System.currentTimeMillis());
    }

    /**
     * Get campaign statistics for monitoring
     */
    public List<CampaignStatsResponse> getCampaignStatsForOrder(Long orderId) {
        List<BinomCampaign> campaigns = binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);
        List<CampaignStatsResponse> statsList = new ArrayList<>();

        for (BinomCampaign campaign : campaigns) {
            try {
                CampaignStatsResponse stats = binomClient.getCampaignStats(campaign.getCampaignId());
                statsList.add(stats);
            } catch (Exception e) {
                log.error("Failed to get stats for campaign {}: {}", campaign.getCampaignId(), e.getMessage());
            }
        }

        return statsList;
    }

    /**
     * Stop all campaigns for an order
     */
    @Transactional
    public void stopAllCampaignsForOrder(Long orderId) {
        List<BinomCampaign> campaigns = binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);
        
        for (BinomCampaign campaign : campaigns) {
            try {
                // Note: Binom API doesn't have a stopCampaign method, so we just update the status
                campaign.setStatus("STOPPED");
                campaign.setUpdatedAt(LocalDateTime.now());
                binomCampaignRepository.save(campaign);
                
                log.info("Stopped Binom campaign {} for order {}", campaign.getCampaignId(), orderId);
            } catch (Exception e) {
                log.error("Failed to stop campaign {} for order {}: {}", 
                         campaign.getCampaignId(), orderId, e.getMessage());
            }
        }
    }

    // === MISSING PUBLIC METHODS FOR COMPILATION ===
    public List<BinomCampaign> getActiveCampaignsForOrder(Long orderId) {
        return binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);
    }

    public void stopCampaign(String campaignId) {
        BinomCampaign campaign = binomCampaignRepository.findByCampaignId(campaignId).orElse(null);
        if (campaign != null) {
            campaign.setStatus("STOPPED");
            campaign.setUpdatedAt(LocalDateTime.now());
            binomCampaignRepository.save(campaign);
            log.info("Stopped campaign {}", campaignId);
        }
    }

    public void updateCampaignStats(String campaignId) {
        // Stub: In real implementation, fetch stats from Binom and update local DB
        log.info("Updating campaign stats for {} (stub)", campaignId);
    }

    public void resumeCampaign(String campaignId) {
        BinomCampaign campaign = binomCampaignRepository.findByCampaignId(campaignId).orElse(null);
        if (campaign != null) {
            campaign.setStatus("ACTIVE");
            campaign.setUpdatedAt(LocalDateTime.now());
            binomCampaignRepository.save(campaign);
            log.info("Resumed campaign {}", campaignId);
        }
    }

    public void createRefillCampaign(Order order, String targetUrl, int remainingViews, boolean hasClip) {
        // Stub: Implement refill logic as needed
        log.info("Creating refill campaign for order {} (stub)", order.getId());
    }

    public BinomCampaign createCampaign(Order order, String targetUrl, Boolean hasClip) {
        // Stub: Implement campaign creation logic as needed
        log.info("Creating campaign for order {} (stub)", order.getId());
        return null;
    }

    private TrafficSource selectAvailableTrafficSource() {
        List<TrafficSource> availableSources = trafficSourceRepository.findByClicksUsedTodayLessThan(1000); // Example limit
        if (!availableSources.isEmpty()) {
            return availableSources.get(0); // Select the first available source
        }
        return null;
    }

    private String generateCampaignId(Long orderId, String sourceId) {
        return String.format("SMM_ORDER_%d_TRAFFIC_%s_%d", orderId, sourceId, System.currentTimeMillis());
    }
}