package com.smmpanel.service;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * PRODUCTION-READY Binom Integration Service
 * Handles all Binom tracker integrations with proper error handling and monitoring
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinomService {

    private final BinomClient binomClient;
    private final OrderRepository orderRepository;
    private final BinomCampaignRepository binomCampaignRepository;
    private final FixedBinomCampaignRepository fixedBinomCampaignRepository;
    private final TrafficSourceRepository trafficSourceRepository;
    private final ConversionCoefficientRepository conversionCoefficientRepository;

    @Value("${app.binom.default-coefficient:3.0}")
    private BigDecimal defaultCoefficient;

    @Value("${app.binom.max-campaigns-per-order:5}")
    private int maxCampaignsPerOrder;

    /**
     * CORE METHOD: Create Binom integration for order
     */
    @Transactional
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public BinomIntegrationResponse createBinomIntegration(BinomIntegrationRequest request) {
        try {
            log.info("Creating Binom integration for order: {}", request.getOrderId());

            // 1. Validate order exists
            Order order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + request.getOrderId()));

            // 2. Get conversion coefficient
            BigDecimal coefficient = getConversionCoefficient(order.getService().getId(), request.getClipCreated());

            // 3. Calculate required clicks
            int clicksRequired = calculateRequiredClicks(request.getTargetViews(), coefficient);

            // 4. Create offer in Binom
            String offerName = generateOfferName(request.getOrderId(), request.getClipCreated());
            String offerId = createOrGetOffer(offerName, request.getTargetUrl(), request.getGeoTargeting());

            // 5. Create or assign campaigns
            List<String> campaignIds = createOrAssignCampaigns(order, offerId, clicksRequired, request.getGeoTargeting());

            // 6. Save Binom campaign records
            saveBinomCampaigns(order, campaignIds, offerId, request.getTargetUrl(), clicksRequired, coefficient);

            log.info("Successfully created Binom integration for order {}: offer={}, campaigns={}, clicks={}", 
                    request.getOrderId(), offerId, campaignIds, clicksRequired);

            return BinomIntegrationResponse.builder()
                    .status("SUCCESS")
                    .message("Binom integration created successfully")
                    .orderId(request.getOrderId())
                    .offerId(offerId)
                    .campaignIds(campaignIds)
                    .clicksRequired(clicksRequired)
                    .coefficient(coefficient)
                    .targetUrl(request.getTargetUrl())
                    .build();

        } catch (Exception e) {
            log.error("Failed to create Binom integration for order {}: {}", request.getOrderId(), e.getMessage(), e);
            
            return BinomIntegrationResponse.builder()
                    .status("ERROR")
                    .message("Failed to create Binom integration: " + e.getMessage())
                    .orderId(request.getOrderId())
                    .build();
        }
    }

    /**
     * Assign offer to existing campaigns (for complex routing)
     */
    @Async("asyncExecutor")
    @Transactional
    public CompletableFuture<Void> assignOfferAsync(OfferAssignmentRequest request) {
        try {
            log.info("Processing async offer assignment for order: {}", request.getOrderId());

            Order order = orderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + request.getOrderId()));

            // Create or get offer
            String offerId = createOrGetOffer(request.getOfferName(), request.getTargetUrl(), request.getGeoTargeting());

            if (request.getUseFixedCampaign() != null && request.getUseFixedCampaign()) {
                // Use fixed campaigns
                assignToFixedCampaigns(order, offerId, request.getTargetUrl());
            } else {
                // Create new campaigns
                BigDecimal coefficient = getConversionCoefficient(order.getService().getId(), request.getTargetUrl().contains("clip"));
                int clicksRequired = calculateRequiredClicks(order.getQuantity(), coefficient);
                
                List<String> campaignIds = createOrAssignCampaigns(order, offerId, clicksRequired, request.getGeoTargeting());
                saveBinomCampaigns(order, campaignIds, offerId, request.getTargetUrl(), clicksRequired, coefficient);
            }

            log.info("Completed async offer assignment for order: {}", request.getOrderId());
            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            log.error("Failed async offer assignment for order {}: {}", request.getOrderId(), e.getMessage(), e);
            throw new RuntimeException("Async offer assignment failed", e);
        }
    }

    /**
     * Get campaign statistics
     */
    public CampaignStatsResponse getCampaignStats(String campaignId) {
        try {
            return binomClient.getCampaignStats(campaignId);
        } catch (Exception e) {
            log.error("Failed to get campaign stats for {}: {}", campaignId, e.getMessage());
            
            // Return empty stats on failure
            return CampaignStatsResponse.builder()
                    .campaignId(campaignId)
                    .clicks(0)
                    .views(0)
                    .conversions(0)
                    .cost(0.0)
                    .build();
        }
    }

    /**
     * Test Binom connection
     */
    public boolean testConnection() {
        try {
            return binomClient.testConnection();
        } catch (Exception e) {
            log.error("Binom connection test failed: {}", e.getMessage());
            return false;
        }
    }

    // ==================== PRIVATE HELPER METHODS ====================

    private BigDecimal getConversionCoefficient(Long serviceId, Boolean clipCreated) {
        Optional<ConversionCoefficient> coefficientOpt = conversionCoefficientRepository
                .findByServiceIdAndWithoutClip(serviceId, clipCreated != null ? !clipCreated : true);
        
        if (coefficientOpt.isPresent()) {
            return coefficientOpt.get().getCoefficient();
        }
        
        // Default coefficients: 3.0 with clip, 4.0 without clip
        return (clipCreated != null && clipCreated) ? 
                new BigDecimal("3.0") : new BigDecimal("4.0");
    }

    private int calculateRequiredClicks(Integer targetViews, BigDecimal coefficient) {
        if (targetViews == null || targetViews <= 0) {
            throw new IllegalArgumentException("Target views must be positive");
        }
        
        BigDecimal clicks = new BigDecimal(targetViews).multiply(coefficient);
        return clicks.setScale(0, RoundingMode.CEILING).intValue();
    }

    private String generateOfferName(Long orderId, Boolean clipCreated) {
        String type = (clipCreated != null && clipCreated) ? "CLIP" : "ORIGINAL";
        return String.format("SMM_%s_Order_%d_%d", type, orderId, System.currentTimeMillis());
    }

    private List<String> createOrAssignCampaigns(Order order, String offerId, int clicksRequired, String geoTargeting) {
        try {
            // Get active traffic sources
            List<TrafficSource> trafficSources = trafficSourceRepository.findActiveSourcesWithCapacity();
            
            if (trafficSources.isEmpty()) {
                throw new RuntimeException("No active traffic sources available");
            }

            List<String> campaignIds = new java.util.ArrayList<>();

            // Distribute clicks across traffic sources
            int remainingClicks = clicksRequired;
            int sourcesUsed = 0;

            for (TrafficSource source : trafficSources) {
                if (sourcesUsed >= maxCampaignsPerOrder || remainingClicks <= 0) {
                    break;
                }

                // Calculate clicks for this source based on weight and capacity
                int sourceClicks = calculateSourceClicks(source, remainingClicks, trafficSources.size() - sourcesUsed);

                if (sourceClicks > 0) {
                    String campaignId = createCampaignForSource(order, source, offerId, sourceClicks, geoTargeting);
                    campaignIds.add(campaignId);
                    remainingClicks -= sourceClicks;
                    sourcesUsed++;
                }
            }

            if (campaignIds.isEmpty()) {
                throw new RuntimeException("Failed to create any campaigns");
            }

            return campaignIds;

        } catch (Exception e) {
            log.error("Failed to create/assign campaigns for order {}: {}", order.getId(), e.getMessage());
            throw new RuntimeException("Campaign creation failed", e);
        }
    }

    private String createCampaignForSource(Order order, TrafficSource source, String offerId, 
                                         int clicks, String geoTargeting) {
        try {
            String campaignName = String.format("SMM_Order_%d_%s_%d", 
                    order.getId(), source.getSourceId(), System.currentTimeMillis());

            CreateCampaignRequest campaignRequest = CreateCampaignRequest.builder()
                    .name(campaignName)
                    .trafficSourceId(source.getSourceId())
                    .description(String.format("Auto-generated campaign for order %d", order.getId()))
                    .geoTargeting(geoTargeting != null ? geoTargeting : "US")
                    .status("ACTIVE")
                    .build();

            CreateCampaignResponse campaignResponse = binomClient.createCampaign(campaignRequest);
            
            // Assign offer to campaign
            binomClient.assignOfferToCampaign(campaignResponse.getCampaignId(), offerId);

            return campaignResponse.getCampaignId();

        } catch (Exception e) {
            log.error("Failed to create campaign for source {}: {}", source.getSourceId(), e.getMessage());
            throw new RuntimeException("Campaign creation failed for source " + source.getSourceId(), e);
        }
    }

    private int calculateSourceClicks(TrafficSource source, int remainingClicks, int remainingSources) {
        // Calculate based on source weight and available capacity
        int dailyCapacity = source.getDailyLimit() != null ? 
                source.getDailyLimit() - source.getClicksUsedToday() : Integer.MAX_VALUE;

        if (dailyCapacity <= 0) {
            return 0; // Source at capacity
        }

        // Distribute clicks proportionally
        int baseClicks = remainingClicks / remainingSources;
        int weightedClicks = (int) (baseClicks * (source.getWeight() / 100.0));

        return Math.min(weightedClicks, dailyCapacity);
    }

    private void assignToFixedCampaigns(Order order, String offerId, String targetUrl) {
        try {
            List<FixedBinomCampaign> fixedCampaigns = fixedBinomCampaignRepository.findActiveByGeoTargeting(
                    order.getService().getGeoTargeting() != null ? order.getService().getGeoTargeting() : "US");

            if (fixedCampaigns.isEmpty()) {
                throw new RuntimeException("No fixed campaigns available for geo targeting");
            }

            for (FixedBinomCampaign fixedCampaign : fixedCampaigns) {
                try {
                    // Assign offer to fixed campaign
                    binomClient.assignOfferToCampaign(fixedCampaign.getCampaignId(), offerId);

                    // Create campaign record
                    BinomCampaign campaign = BinomCampaign.builder()
                            .order(order)
                            .campaignId(fixedCampaign.getCampaignId())
                            .offerId(offerId)
                            .targetUrl(targetUrl)
                            .trafficSource(fixedCampaign.getTrafficSource())
                            .fixedCampaign(fixedCampaign)
                            .coefficient(defaultCoefficient)
                            .clicksRequired(calculateRequiredClicks(order.getQuantity(), defaultCoefficient))
                            .status("ACTIVE")
                            .isFixedCampaign(true)
                            .build();

                    binomCampaignRepository.save(campaign);
                    log.info("Assigned offer {} to fixed campaign {}", offerId, fixedCampaign.getCampaignId());

                } catch (Exception e) {
                    log.error("Failed to assign offer to fixed campaign {}: {}", 
                            fixedCampaign.getCampaignId(), e.getMessage());
                    // Continue with other campaigns
                }
            }

        } catch (Exception e) {
            log.error("Failed to assign to fixed campaigns for order {}: {}", order.getId(), e.getMessage());
            throw new RuntimeException("Fixed campaign assignment failed", e);
        }
    }

    private void saveBinomCampaigns(Order order, List<String> campaignIds, String offerId, 
                                  String targetUrl, int totalClicksRequired, BigDecimal coefficient) {
        try {
            for (String campaignId : campaignIds) {
                // Calculate clicks for this specific campaign
                int campaignClicks = totalClicksRequired / campaignIds.size();
                if (campaignIds.indexOf(campaignId) < (totalClicksRequired % campaignIds.size())) {
                    campaignClicks++; // Distribute remainder
                }

                BinomCampaign campaign = BinomCampaign.builder()
                        .order(order)
                        .campaignId(campaignId)
                        .offerId(offerId)
                        .targetUrl(targetUrl)
                        .coefficient(coefficient)
                        .clicksRequired(campaignClicks)
                        .clicksDelivered(0)
                        .viewsGenerated(0)
                        .status("ACTIVE")
                        .isFixedCampaign(false)
                        .build();

                binomCampaignRepository.save(campaign);
            }

            log.info("Saved {} Binom campaign records for order {}", campaignIds.size(), order.getId());

        } catch (Exception e) {
            log.error("Failed to save Binom campaigns for order {}: {}", order.getId(), e.getMessage());
            throw new RuntimeException("Failed to save campaign records", e);
        }
    }

    private String createOrGetOffer(String offerName, String targetUrl, String geoTargeting) {
        try {
            // Check if offer already exists
            CheckOfferResponse checkResponse = binomClient.checkOfferExists(offerName);
            
            if (checkResponse.getExists()) {
                log.info("Using existing offer: {} -> {}", offerName, checkResponse.getOfferId());
                return checkResponse.getOfferId();
            }

            // Create new offer
            CreateOfferRequest offerRequest = CreateOfferRequest.builder()
                    .name(offerName)
                    .url(targetUrl)
                    .description("Auto-generated offer for SMM Panel order")
                    .geoTargeting(geoTargeting != null ? geoTargeting : "US")
                    .category("SMM_YOUTUBE")
                    .status("ACTIVE")
                    .build();

            CreateOfferResponse offerResponse = binomClient.createOffer(offerRequest);
            return offerResponse.getOfferId();

        } catch (Exception e) {
            log.error("Failed to create/get offer {}: {}", offerName, e.getMessage());
            throw new RuntimeException("Offer creation failed", e);
        }
    }



    /**
     * Get all campaigns for an order
     */
    public List<BinomCampaign> getOrderCampaigns(Long orderId) {
        return binomCampaignRepository.findByOrderId(orderId);
    }

    /**
     * Pause/Resume campaign
     */
    @Transactional
    public void updateCampaignStatus(String campaignId, String status) {
        try {
            Optional<BinomCampaign> campaignOpt = binomCampaignRepository.findByCampaignId(campaignId);
            
            if (campaignOpt.isPresent()) {
                BinomCampaign campaign = campaignOpt.get();
                campaign.setStatus(status);
                campaign.setUpdatedAt(LocalDateTime.now());
                
                binomCampaignRepository.save(campaign);
                
                log.info("Updated campaign {} status to {}", campaignId, status);
            }

        } catch (Exception e) {
            log.error("Failed to update campaign {} status: {}", campaignId, e.getMessage());
            throw new RuntimeException("Campaign status update failed", e);
        }
    }

    @Transactional
    public BinomCampaign createCampaign(Order order, String targetUrl, boolean hasClip) {
        try {
            // Get conversion coefficient
            BigDecimal coefficient = getConversionCoefficient(order.getService().getId(), hasClip);
            
            // Calculate required clicks
            int requiredClicks = (int) (order.getQuantity() * coefficient.doubleValue());
            
            // Select traffic source
            TrafficSource trafficSource = selectTrafficSource();
            
            // Create campaign in Binom
            CreateCampaignRequest request = CreateCampaignRequest.builder()
                    .name("SMM_Order_" + order.getId())
                    .url(targetUrl)
                    .trafficSourceId(trafficSource.getSourceId())
                    .countryCode("US") // Default, can be made configurable
                    .clicksLimit(requiredClicks)
                    .dailyLimit(Math.min(requiredClicks, 1000)) // Max 1000 clicks per day
                    .build();

            CampaignResponse response = binomClient.createCampaign(request);

            // Save campaign record
            BinomCampaign campaign = new BinomCampaign();
            campaign.setOrder(order);
            campaign.setCampaignId(response.getCampaignId());
            campaign.setOfferId(response.getOfferId());
            campaign.setTargetUrl(targetUrl);
            campaign.setTrafficSource(trafficSource);
            campaign.setCoefficient(coefficient);
            campaign.setClicksRequired(requiredClicks);
            campaign.setClicksDelivered(0);
            campaign.setViewsGenerated(0);
            campaign.setStatus("ACTIVE");

            campaign = campaignRepository.save(campaign);

            log.info("Created Binom campaign {} for order {} with {} required clicks", 
                    response.getCampaignId(), order.getId(), requiredClicks);

            return campaign;

        } catch (Exception e) {
            log.error("Failed to create Binom campaign for order {}: {}", order.getId(), e.getMessage(), e);
            throw new BinomApiException("Failed to create campaign: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void updateCampaignStats(String campaignId) {
        try {
            BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

            CampaignStats stats = binomClient.getCampaignStats(campaignId);

            campaign.setClicksDelivered(stats.getClicks());
            campaign.setViewsGenerated(stats.getConversions());

            // Update traffic source usage
            TrafficSource source = campaign.getTrafficSource();
            if (source.getLastResetDate().isBefore(LocalDate.now())) {
                source.setClicksUsedToday(0);
                source.setLastResetDate(LocalDate.now());
            }
            source.setClicksUsedToday(source.getClicksUsedToday() + stats.getClicks());
            trafficSourceRepository.save(source);

            campaignRepository.save(campaign);

            log.debug("Updated campaign {} stats: {} clicks, {} conversions", 
                    campaignId, stats.getClicks(), stats.getConversions());

        } catch (Exception e) {
            log.error("Failed to update campaign stats for {}: {}", campaignId, e.getMessage(), e);
        }
    }

    @Transactional
    public void stopCampaign(String campaignId) {
        try {
            BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

            binomClient.stopCampaign(campaignId);
            
            campaign.setStatus("STOPPED");
            campaignRepository.save(campaign);

            log.info("Stopped Binom campaign {}", campaignId);

        } catch (Exception e) {
            log.error("Failed to stop campaign {}: {}", campaignId, e.getMessage(), e);
            throw new BinomApiException("Failed to stop campaign: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void resumeCampaign(String campaignId) {
        try {
            BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

            binomClient.resumeCampaign(campaignId);
            
            campaign.setStatus("ACTIVE");
            campaignRepository.save(campaign);

            log.info("Resumed Binom campaign {}", campaignId);

        } catch (Exception e) {
            log.error("Failed to resume campaign {}: {}", campaignId, e.getMessage(), e);
            throw new BinomApiException("Failed to resume campaign: " + e.getMessage(), e);
        }
    }

    @Transactional
    public BinomCampaign createRefillCampaign(Order order, String targetUrl, int remainingViews, boolean hasClip) {
        try {
            // Get conversion coefficient
            BigDecimal coefficient = getConversionCoefficient(order.getService().getId(), hasClip);
            
            // Calculate required clicks for remaining views
            int requiredClicks = (int) (remainingViews * coefficient.doubleValue());
            
            // Select traffic source
            TrafficSource trafficSource = selectTrafficSource();
            
            // Create refill campaign in Binom
            CreateCampaignRequest request = CreateCampaignRequest.builder()
                    .name("SMM_Refill_" + order.getId() + "_" + System.currentTimeMillis())
                    .url(targetUrl)
                    .trafficSourceId(trafficSource.getSourceId())
                    .countryCode("US")
                    .clicksLimit(requiredClicks)
                    .dailyLimit(Math.min(requiredClicks, 500)) // Slower refill rate
                    .build();

            CampaignResponse response = binomClient.createCampaign(request);

            // Save refill campaign record
            BinomCampaign campaign = new BinomCampaign();
            campaign.setOrder(order);
            campaign.setCampaignId(response.getCampaignId());
            campaign.setOfferId(response.getOfferId());
            campaign.setTargetUrl(targetUrl);
            campaign.setTrafficSource(trafficSource);
            campaign.setCoefficient(coefficient);
            campaign.setClicksRequired(requiredClicks);
            campaign.setClicksDelivered(0);
            campaign.setViewsGenerated(0);
            campaign.setStatus("ACTIVE");

            campaign = campaignRepository.save(campaign);

            log.info("Created Binom refill campaign {} for order {} with {} required clicks", 
                    response.getCampaignId(), order.getId(), requiredClicks);

            return campaign;

        } catch (Exception e) {
            log.error("Failed to create Binom refill campaign for order {}: {}", order.getId(), e.getMessage(), e);
            throw new BinomApiException("Failed to create refill campaign: " + e.getMessage(), e);
        }
    }

    private BigDecimal getConversionCoefficient(Long serviceId, boolean hasClip) {
        Optional<ConversionCoefficient> coefficient = coefficientRepository.findByServiceId(serviceId);
        
        if (coefficient.isPresent()) {
            return hasClip ? coefficient.get().getWithClip() : coefficient.get().getWithoutClip();
        }
        
        // Default coefficients if not found
        return hasClip ? new BigDecimal("3.0") : new BigDecimal("4.0");
    }

    private TrafficSource selectTrafficSource(Long serviceId) {
        // Определяем качественный уровень по ID услуги
        String qualityLevel = determineQualityLevel(serviceId);
        
        // Получаем источники для конкретного качества
        List<TrafficSource> activeSources = trafficSourceRepository
                .findByActiveTrueAndQualityLevel(qualityLevel);
        
        if (activeSources.isEmpty()) {
            throw new NoAvailableTrafficSourceException("No active traffic sources available for quality: " + qualityLevel);
        }

        // Фильтруем источники по дневным лимитам
        LocalDate today = LocalDate.now();
        List<TrafficSource> availableSources = activeSources.stream()
                .filter(source -> {
                    // Сброс дневных счетчиков при необходимости
                    if (source.getLastResetDate().isBefore(today)) {
                        source.setClicksUsedToday(0);
                        source.setLastResetDate(today);
                        trafficSourceRepository.save(source);
                    }
                    
                    // Проверка лимитов
                    return source.getDailyLimit() == null || 
                           source.getClicksUsedToday() < source.getDailyLimit();
                })
                .toList();

        if (availableSources.isEmpty()) {
            throw new NoAvailableTrafficSourceException("All traffic sources have reached daily limits for quality: " + qualityLevel);
        }

        // Weighted random selection
        int totalWeight = availableSources.stream()
                .mapToInt(TrafficSource::getWeight)
                .sum();

        int randomWeight = random.nextInt(totalWeight);
        int currentWeight = 0;

        for (TrafficSource source : availableSources) {
            currentWeight += source.getWeight();
            if (randomWeight < currentWeight) {
                return source;
            }
        }

        // Fallback
        return availableSources.get(0);
    }

    public List<BinomCampaign> getActiveCampaignsForOrder(Long orderId) {
        return campaignRepository.findByOrderIdAndStatus(orderId, "ACTIVE");
    }

    private String determineQualityLevel(Long serviceId) {
        return switch (serviceId.intValue()) {
            case 1 -> "STANDARD";
            case 2 -> "PREMIUM";
            case 3 -> "HIGH_QUALITY";
            default -> "STANDARD";
        };
    }
}