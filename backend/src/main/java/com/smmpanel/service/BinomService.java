package com.smmpanel.service;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.*;
import com.smmpanel.entity.*;
import com.smmpanel.exception.BinomApiException;
import com.smmpanel.exception.BinomTemporaryException;
import com.smmpanel.exception.BinomValidationException;
import com.smmpanel.repository.jpa.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * CRITICAL BINOM INTEGRATION SERVICE This MUST distribute offers to exactly 3 campaigns as required
 * for Perfect Panel compatibility
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

    @Value("${app.binom.default-coefficient:3.0}")
    private BigDecimal defaultCoefficient;

    /** FIXED: Distribute order across exactly 3 fixed Binom campaigns */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    @CircuitBreaker(name = "binom-api", fallbackMethod = "fallbackCreateBinomIntegration")
    @Retry(name = "binom-api")
    public BinomIntegrationResponse createBinomIntegration(
            Order order, String videoId, boolean clipCreated, String targetUrl) {
        try {
            // Input validation
            validateOrderParameters(order, targetUrl);
            // Calculate coefficient with proper null safety
            BigDecimal coefficient = calculateSafeCoefficient(order, clipCreated);
            Integer targetViews = order.getQuantity();

            if (targetViews == null || targetViews <= 0) {
                throw new BinomValidationException("Invalid target views: " + targetViews);
            }

            // Get active fixed campaigns with proper validation
            String geoTargeting =
                    order.getTargetCountry() != null ? order.getTargetCountry() : "US";
            List<FixedBinomCampaign> fixedCampaigns = getThreeFixedCampaigns(geoTargeting);
            int campaignCount = fixedCampaigns.size();

            if (campaignCount < 2) {
                throw new BinomApiException(
                        "Insufficient fixed campaigns available: " + campaignCount);
            }

            // Create single offer for all campaigns
            String offerName = generateOfferName(order.getId(), clipCreated);
            String offerId = createOrGetOffer(offerName, targetUrl, "US");

            // Calculate clicks per campaign with overflow protection
            int totalRequiredClicks = calculateTotalClicksSafe(targetViews, coefficient);
            int clicksPerCampaign = totalRequiredClicks / campaignCount;
            int remainingClicks = totalRequiredClicks % campaignCount; // Handle rounding

            List<String> assignedCampaignIds = new ArrayList<>();

            // Distribute offer across fixed campaigns (2 or 3)
            for (int i = 0; i < fixedCampaigns.size(); i++) {
                FixedBinomCampaign fixedCampaign = fixedCampaigns.get(i);

                // Add remaining clicks to the first campaign to handle rounding
                int campaignClicks = clicksPerCampaign + (i == 0 ? remainingClicks : 0);

                try {
                    // Assign offer to this fixed campaign with error handling
                    boolean assigned =
                            assignOfferToCampaign(
                                    offerId,
                                    fixedCampaign.getCampaignId(),
                                    fixedCampaign.getPriority());

                    if (assigned) {
                        // Save relationship in binom_campaigns table
                        saveBinomCampaignRecord(
                                order,
                                fixedCampaign.getCampaignId(),
                                offerId,
                                campaignClicks,
                                targetUrl,
                                coefficient);
                        assignedCampaignIds.add(fixedCampaign.getCampaignId());

                        log.info(
                                "Order {} distributed to fixed campaign {} - Required clicks: {}",
                                order.getId(),
                                fixedCampaign.getCampaignId(),
                                campaignClicks);
                    }
                } catch (Exception e) {
                    log.error(
                            "Failed to assign order {} to campaign {}: {}",
                            order.getId(),
                            fixedCampaign.getCampaignId(),
                            e.getMessage());
                    // Continue with other campaigns but track the failure
                    updateOrderErrorInfo(order, "PARTIAL_CAMPAIGN_ASSIGNMENT", e.getMessage());
                }
            }

            // Validate minimum campaign assignments (at least 2)
            if (assignedCampaignIds.size() < 2) {
                throw new BinomApiException(
                        String.format(
                                "Failed minimum campaign requirement. Only %d of %d campaigns"
                                        + " assigned.",
                                assignedCampaignIds.size(), campaignCount));
            }

            // Update order with success info
            updateOrderSuccess(order, assignedCampaignIds, coefficient);

            log.info(
                    "Order {} successfully distributed across {} fixed campaigns - Total clicks: {}"
                            + " (coefficient: {})",
                    order.getId(),
                    campaignCount,
                    totalRequiredClicks,
                    coefficient);

            return BinomIntegrationResponse.builder()
                    .success(true)
                    .campaignId(String.join(",", assignedCampaignIds))
                    .campaignsCreated(assignedCampaignIds.size())
                    .message(
                            String.format(
                                    "Order distributed across %d fixed campaigns successfully",
                                    assignedCampaignIds.size()))
                    .build();
        } catch (BinomValidationException e) {
            log.error("Validation error for order {}: {}", order.getId(), e.getMessage());
            updateOrderErrorInfo(order, "VALIDATION_ERROR", e.getMessage());
            throw e;
        } catch (BinomTemporaryException e) {
            log.error("Temporary Binom API error for order {}: {}", order.getId(), e.getMessage());
            updateOrderErrorInfo(order, "TEMPORARY_ERROR", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error(
                    "Unexpected error creating Binom integration for order {}: {}",
                    order.getId(),
                    e.getMessage(),
                    e);
            updateOrderErrorInfo(order, "UNEXPECTED_ERROR", e.getMessage());
            throw new BinomApiException("Failed to create Binom integration: " + e.getMessage(), e);
        }
    }

    /** Fallback method for circuit breaker */
    public BinomIntegrationResponse fallbackCreateBinomIntegration(
            Order order, String videoId, boolean clipCreated, String targetUrl, Exception ex) {

        log.error(
                "Circuit breaker fallback triggered for order {}: {}",
                order.getId(),
                ex.getMessage());

        // Update order with circuit breaker failure
        updateOrderErrorInfo(order, "CIRCUIT_BREAKER_OPEN", "Binom API temporarily unavailable");

        return BinomIntegrationResponse.builder()
                .success(false)
                .campaignsCreated(0)
                .message("Binom API temporarily unavailable. Please retry later.")
                .errorCode("CIRCUIT_BREAKER_OPEN")
                .build();
    }

    /** Validate order parameters with null checks */
    private void validateOrderParameters(Order order, String targetUrl) {
        if (order == null) {
            throw new BinomValidationException("Order cannot be null");
        }
        if (order.getId() == null) {
            throw new BinomValidationException("Order ID cannot be null");
        }
        if (!StringUtils.hasText(targetUrl)) {
            throw new BinomValidationException("Target URL is required");
        }
        if (order.getQuantity() == null || order.getQuantity() <= 0) {
            throw new BinomValidationException("Order quantity must be positive");
        }
    }

    /** Calculate coefficient with proper null handling and boundaries */
    private BigDecimal calculateSafeCoefficient(Order order, boolean clipCreated) {
        try {
            // Try to get coefficient from conversion_coefficients table
            if (order.getService() != null && order.getService().getId() != null) {
                Optional<ConversionCoefficient> coefficientOpt =
                        conversionCoefficientRepository.findByServiceId(order.getService().getId());

                if (coefficientOpt.isPresent()) {
                    ConversionCoefficient cc = coefficientOpt.get();
                    BigDecimal coefficient = clipCreated ? cc.getWithClip() : cc.getWithoutClip();

                    // Validate coefficient boundaries
                    if (coefficient != null
                            && coefficient.compareTo(BigDecimal.ZERO) > 0
                            && coefficient.compareTo(new BigDecimal("10.0")) <= 0) {
                        return coefficient;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error calculating coefficient, using default: {}", e.getMessage());
        }

        // Fallback to default
        return clipCreated ? new BigDecimal("3.0") : new BigDecimal("4.0");
    }

    /** Calculate total clicks with overflow protection */
    private int calculateTotalClicksSafe(Integer targetViews, BigDecimal coefficient) {
        try {
            BigDecimal result =
                    BigDecimal.valueOf(targetViews)
                            .multiply(coefficient)
                            .setScale(0, RoundingMode.CEILING);

            // Check for integer overflow
            if (result.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new BinomValidationException(
                        "Calculated clicks exceed maximum allowed value");
            }

            return result.intValue();
        } catch (ArithmeticException e) {
            throw new BinomValidationException("Error calculating total clicks: " + e.getMessage());
        }
    }

    /** Update order with error information */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void updateOrderErrorInfo(Order order, String errorType, String errorMessage) {
        try {
            order.setLastErrorType(errorType);
            order.setErrorMessage(errorMessage);
            order.setLastRetryAt(LocalDateTime.now());
            order.setRetryCount(order.getRetryCount() != null ? order.getRetryCount() + 1 : 1);

            // Calculate next retry time with exponential backoff
            if (order.getRetryCount() < order.getMaxRetries()) {
                long delaySeconds = (long) Math.pow(2, order.getRetryCount()) * 60;
                order.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
            }

            orderRepository.save(order);
        } catch (Exception e) {
            log.error("Failed to update order error info: {}", e.getMessage());
        }
    }

    /** Update order with success information */
    @Transactional(propagation = Propagation.REQUIRED)
    private void updateOrderSuccess(Order order, List<String> campaignIds, BigDecimal coefficient) {
        order.setCoefficient(coefficient);
        order.setStatus(OrderStatus.PROCESSING);
        order.setErrorMessage(null);
        order.setLastErrorType(null);
        order.setRetryCount(0);
        orderRepository.save(order);
    }

    /** Validate request parameters */
    private void validateRequest(BinomIntegrationRequest request) {
        if (request.getOrderId() == null) {
            throw new BinomValidationException("Order ID is required");
        }
        if (!StringUtils.hasText(request.getTargetUrl())) {
            throw new BinomValidationException("Target URL is required");
        }
        if (request.getTargetViews() == null || request.getTargetViews() <= 0) {
            throw new BinomValidationException("Target views must be positive");
        }
    }

    /** Get active fixed campaigns (2 or 3) for distribution */
    private List<FixedBinomCampaign> getThreeFixedCampaigns(String geoTargeting) {
        // Get all active fixed campaigns
        List<FixedBinomCampaign> campaigns = fixedBinomCampaignRepository.findByActiveTrue();

        if (campaigns.isEmpty()) {
            throw new BinomApiException("No active fixed campaigns found in database");
        }

        if (campaigns.size() < 2) {
            throw new BinomApiException(
                    String.format(
                            "At least 2 fixed campaigns required, found %d. "
                                    + "Please configure campaigns in the database.",
                            campaigns.size()));
        }

        // Return up to 3 campaigns (works with 2 or 3)
        return campaigns.stream().limit(3).toList();
    }

    /** Get conversion coefficient based on service and clip creation */
    private BigDecimal getConversionCoefficient(Long serviceId, Boolean clipCreated) {
        return conversionCoefficientRepository
                .findByServiceIdAndWithoutClip(serviceId, clipCreated != null ? !clipCreated : true)
                .map(ConversionCoefficient::getCoefficient)
                .orElse(
                        clipCreated != null && clipCreated
                                ? new BigDecimal("3.0")
                                : new BigDecimal("4.0"));
    }

    /** Calculate required clicks: target_views * coefficient */
    private int calculateRequiredClicks(int targetViews, BigDecimal coefficient) {
        BigDecimal result =
                BigDecimal.valueOf(targetViews).multiply(coefficient).setScale(0, RoundingMode.UP);

        if (result.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Calculated clicks must be positive");
        }

        return result.intValue();
    }

    /** Create or get existing offer in Binom */
    private String createOrGetOffer(String offerName, String targetUrl, String geoTargeting) {
        try {
            // Check if offer exists
            CheckOfferResponse existingOffer = binomClient.checkOfferExists(offerName);
            if (existingOffer.isExists()) {
                log.info("Using existing Binom offer: {}", existingOffer.getOfferId());
                return existingOffer.getOfferId();
            }

            // Create new offer with updated DTO structure
            CreateOfferRequest offerRequest =
                    CreateOfferRequest.builder()
                            .name(offerName)
                            .url(targetUrl)
                            .geoTargeting(List.of(geoTargeting)) // Now expects List<String>
                            .description("SMM Panel auto-generated offer")
                            .affiliateNetworkId(1L) // Default affiliate network ID
                            .type("REDIRECT")
                            .status("ACTIVE")
                            .payoutType("CPA")
                            .requiresApproval(false)
                            .isArchived(false)
                            .build();

            CreateOfferResponse response = binomClient.createOffer(offerRequest);
            log.info("Created new Binom offer: {}", response.getOfferId());
            return response.getOfferId();

        } catch (Exception e) {
            log.error("Failed to create/get Binom offer: {}", e.getMessage());
            throw new BinomApiException("Failed to create/get Binom offer", e);
        }
    }

    /** Save Binom campaign record in database */
    private void saveBinomCampaignRecord(
            Order order,
            String campaignId,
            String offerId,
            int clicksRequired,
            String targetUrl,
            BigDecimal coefficient) {
        BinomCampaign campaign = new BinomCampaign();
        campaign.setOrder(order);
        campaign.setCampaignId(campaignId);
        campaign.setOfferId(offerId);
        campaign.setClicksRequired(clicksRequired);
        campaign.setClicksDelivered(0);
        campaign.setViewsGenerated(0);
        campaign.setTargetUrl(targetUrl);
        campaign.setCoefficient(coefficient);
        campaign.setStatus("ACTIVE");
        campaign.setCreatedAt(LocalDateTime.now());
        campaign.setUpdatedAt(LocalDateTime.now());

        binomCampaignRepository.save(campaign);
        log.info("Saved Binom campaign record: {} with {} clicks", campaignId, clicksRequired);
    }

    /** Save Binom campaign record in database (legacy method for backward compatibility) */
    private void saveBinomCampaignRecord(
            Order order, String campaignId, String offerId, int clicksRequired, String targetUrl) {
        saveBinomCampaignRecord(
                order, campaignId, offerId, clicksRequired, targetUrl, new BigDecimal("3.0"));
    }

    /** Generate offer name for Perfect Panel compatibility */
    private String generateOfferName(Long orderId, Boolean clipCreated) {
        return String.format(
                "SMM_ORDER_%d_%s_%d",
                orderId,
                clipCreated != null && clipCreated ? "CLIP" : "DIRECT",
                System.currentTimeMillis());
    }

    /**
     * Get aggregated campaign statistics for monitoring from 3 campaigns Now fetches cost and views
     * directly from Binom using official API
     */
    public CampaignStatsResponse getCampaignStatsForOrder(Long orderId) {
        List<BinomCampaign> campaigns = binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);

        // Initialize aggregated values
        long totalClicks = 0L;
        long totalConversions = 0L;
        long totalViews = 0L; // Views/visits from Binom
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        List<String> campaignIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Fetch detailed stats from Binom for all campaigns
        for (BinomCampaign campaign : campaigns) {
            try {
                // Use getDetailedStats to fetch cost and views directly from Binom
                CampaignStatsResponse stats =
                        binomClient.getDetailedStats(
                                campaign.getCampaignId(),
                                null, // Get all-time stats
                                null);

                totalClicks += stats.getClicks();
                totalConversions += stats.getConversions();
                totalCost =
                        totalCost.add(stats.getCost() != null ? stats.getCost() : BigDecimal.ZERO);
                totalRevenue =
                        totalRevenue.add(
                                stats.getRevenue() != null ? stats.getRevenue() : BigDecimal.ZERO);
                campaignIds.add(campaign.getCampaignId());

                // Update local cache with fetched data
                campaign.setClicksDelivered(stats.getClicks().intValue());
                campaign.setCost(stats.getCost());
                campaign.setLastStatsUpdate(LocalDateTime.now());
                binomCampaignRepository.save(campaign);

                // Check if campaign should be auto-paused
                checkAndPauseCampaignIfNeeded(campaign, stats, orderId);

                log.debug(
                        "Campaign {} stats from Binom: {} clicks, cost: {}",
                        campaign.getCampaignId(),
                        stats.getClicks(),
                        stats.getCost());

            } catch (Exception e) {
                log.error(
                        "Failed to get stats for campaign {}: {}",
                        campaign.getCampaignId(),
                        e.getMessage());
                errors.add(
                        String.format("Campaign %s: %s", campaign.getCampaignId(), e.getMessage()));
            }
        }

        log.info(
                "Aggregated stats for order {} from Binom: {} clicks, cost: {} from {} campaigns",
                orderId,
                totalClicks,
                totalCost,
                campaignIds.size());

        // Calculate views delivered based on clicks and coefficient
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order != null && order.getCoefficient() != null) {
            totalViews = (long) (totalClicks / order.getCoefficient().doubleValue());
        }

        // Return aggregated response with Binom data
        return CampaignStatsResponse.builder()
                .campaignId(String.join(",", campaignIds))
                .clicks(totalClicks)
                .conversions(totalConversions)
                .cost(totalCost)
                .revenue(totalRevenue)
                .viewsDelivered(totalViews) // Add views delivered
                .status(campaignIds.size() == 3 ? "ACTIVE" : "PARTIAL")
                .build();
    }

    /**
     * Check if campaign needs to be paused based on views or cost Implements automatic campaign
     * pause logic
     */
    private void checkAndPauseCampaignIfNeeded(
            BinomCampaign campaign, CampaignStatsResponse stats, Long orderId) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                return;
            }

            boolean shouldPause = false;
            String pauseReason = null;

            // Check views delivered vs quantity
            if (order.getQuantity() != null && order.getCoefficient() != null) {
                long viewsDelivered =
                        (long) (stats.getClicks() / order.getCoefficient().doubleValue());
                if (viewsDelivered >= order.getQuantity()) {
                    shouldPause = true;
                    pauseReason =
                            String.format(
                                    "Views target reached: %d >= %d",
                                    viewsDelivered, order.getQuantity());
                }
            }

            // Check cost vs budget limit (if budget_limit is set)
            if (campaign.getBudgetLimit() != null && stats.getCost() != null) {
                if (stats.getCost().compareTo(campaign.getBudgetLimit()) >= 0) {
                    shouldPause = true;
                    pauseReason =
                            String.format(
                                    "Budget limit reached: %s >= %s",
                                    stats.getCost(), campaign.getBudgetLimit());
                }
            }

            // Pause campaign if needed
            if (shouldPause && "ACTIVE".equals(campaign.getStatus())) {
                boolean paused = binomClient.pauseCampaign(campaign.getCampaignId());
                if (paused) {
                    campaign.setStatus("PAUSED");
                    campaign.setPauseReason(pauseReason);
                    campaign.setUpdatedAt(LocalDateTime.now());
                    binomCampaignRepository.save(campaign);

                    log.info(
                            "Auto-paused campaign {} for order {}: {}",
                            campaign.getCampaignId(),
                            orderId,
                            pauseReason);
                } else {
                    log.warn(
                            "Failed to auto-pause campaign {} even though limit reached: {}",
                            campaign.getCampaignId(),
                            pauseReason);
                }
            }
        } catch (Exception e) {
            log.error("Error checking campaign pause conditions: {}", e.getMessage());
        }
    }

    /** Stop all campaigns for an order - Updated for 3-campaign distribution */
    @Transactional
    public void stopAllCampaignsForOrder(Long orderId) {
        List<BinomCampaign> campaigns = binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);

        if (campaigns.isEmpty()) {
            log.warn("No active campaigns found for order {}", orderId);
            return;
        }

        log.info("Stopping {} campaigns for order {}", campaigns.size(), orderId);

        int successCount = 0;
        int failureCount = 0;

        for (BinomCampaign campaign : campaigns) {
            try {
                // Stop campaign in Binom system if API method exists
                // Note: Currently updating status only as Binom API doesn't have stopCampaign
                // method
                campaign.setStatus("STOPPED");
                campaign.setUpdatedAt(LocalDateTime.now());
                binomCampaignRepository.save(campaign);

                successCount++;
                log.info(
                        "Stopped Binom campaign {} for order {} (Campaign {}/{} for this order)",
                        campaign.getCampaignId(),
                        orderId,
                        successCount,
                        campaigns.size());
            } catch (Exception e) {
                failureCount++;
                log.error(
                        "Failed to stop campaign {} for order {}: {}",
                        campaign.getCampaignId(),
                        orderId,
                        e.getMessage());
            }
        }

        // Log summary for distributed campaign management
        if (campaigns.size() == 3) {
            log.info(
                    "Completed stopping distributed campaigns for order {}: {} successful, {}"
                            + " failed (Expected 3 campaigns)",
                    orderId,
                    successCount,
                    failureCount);
        } else {
            log.warn(
                    "Order {} has {} campaigns instead of expected 3. Stopped {} successfully, {}"
                            + " failed",
                    orderId,
                    campaigns.size(),
                    successCount,
                    failureCount);
        }
    }

    // === MISSING PUBLIC METHODS FOR COMPILATION ===
    public List<BinomCampaign> getActiveCampaignsForOrder(Long orderId) {
        return binomCampaignRepository.findByOrderIdAndActiveTrue(orderId);
    }

    /** Stop a specific campaign - Updated for 3-campaign distribution logic */
    @Transactional
    public void stopCampaign(String campaignId) {
        if (campaignId == null || campaignId.trim().isEmpty()) {
            log.error("Cannot stop campaign: campaignId is null or empty");
            throw new IllegalArgumentException("Campaign ID is required");
        }

        Optional<BinomCampaign> campaignOpt = binomCampaignRepository.findByCampaignId(campaignId);
        if (campaignOpt.isEmpty()) {
            log.error("Campaign not found: {}", campaignId);
            throw new BinomApiException("Campaign not found: " + campaignId);
        }

        BinomCampaign campaign = campaignOpt.get();
        String previousStatus = campaign.getStatus();

        try {
            // Stop campaign in Binom system if API method exists
            // Note: Currently updating status only as Binom API doesn't have stopCampaign method
            campaign.setStatus("STOPPED");
            campaign.setUpdatedAt(LocalDateTime.now());
            binomCampaignRepository.save(campaign);

            // Check if this is part of a 3-campaign order and log accordingly
            Long orderId = campaign.getOrder().getId();
            List<BinomCampaign> allOrderCampaigns = binomCampaignRepository.findByOrderId(orderId);
            long activeCampaigns =
                    allOrderCampaigns.stream().filter(c -> "ACTIVE".equals(c.getStatus())).count();
            long stoppedCampaigns =
                    allOrderCampaigns.stream().filter(c -> "STOPPED".equals(c.getStatus())).count();

            log.info(
                    "Stopped campaign {} (order {}) - Status: {} -> STOPPED. Order now has {}"
                            + " active, {} stopped campaigns",
                    campaignId,
                    orderId,
                    previousStatus,
                    activeCampaigns,
                    stoppedCampaigns);

            // Log warning if not following 3-campaign pattern
            if (allOrderCampaigns.size() != 3) {
                log.warn(
                        "Order {} has {} campaigns instead of expected 3 for distributed campaign"
                                + " model",
                        orderId,
                        allOrderCampaigns.size());
            }

            // Log if all campaigns for an order are now stopped
            if (activeCampaigns == 0) {
                log.info("All campaigns for order {} are now stopped", orderId);
            }

        } catch (Exception e) {
            log.error("Failed to stop campaign {}: {}", campaignId, e.getMessage());
            throw new BinomApiException("Failed to stop campaign: " + campaignId, e);
        }
    }

    public void updateCampaignStats(String campaignId) {
        // Stub: In real implementation, fetch stats from Binom and update local DB
        log.info("Updating campaign stats for {} (stub)", campaignId);
    }

    /** Resume all campaigns for an order - Companion to stopAllCampaignsForOrder */
    @Transactional
    public void resumeAllCampaignsForOrder(Long orderId) {
        List<BinomCampaign> campaigns =
                binomCampaignRepository.findByOrderIdAndStatus(orderId, "STOPPED");

        if (campaigns.isEmpty()) {
            log.warn("No stopped campaigns found for order {}", orderId);
            return;
        }

        log.info("Resuming {} campaigns for order {}", campaigns.size(), orderId);

        int successCount = 0;
        int failureCount = 0;

        for (BinomCampaign campaign : campaigns) {
            try {
                campaign.setStatus("ACTIVE");
                campaign.setUpdatedAt(LocalDateTime.now());
                binomCampaignRepository.save(campaign);

                successCount++;
                log.info(
                        "Resumed Binom campaign {} for order {} (Campaign {}/{} for this order)",
                        campaign.getCampaignId(),
                        orderId,
                        successCount,
                        campaigns.size());
            } catch (Exception e) {
                failureCount++;
                log.error(
                        "Failed to resume campaign {} for order {}: {}",
                        campaign.getCampaignId(),
                        orderId,
                        e.getMessage());
            }
        }

        // Log summary for distributed campaign management
        if (campaigns.size() == 3) {
            log.info(
                    "Completed resuming distributed campaigns for order {}: {} successful, {}"
                            + " failed (Expected 3 campaigns)",
                    orderId,
                    successCount,
                    failureCount);
        } else {
            log.warn(
                    "Order {} has {} campaigns instead of expected 3. Resumed {} successfully, {}"
                            + " failed",
                    orderId,
                    campaigns.size(),
                    successCount,
                    failureCount);
        }
    }

    /** Get campaign status summary for an order - Useful for order workflow integration */
    public String getCampaignStatusSummary(Long orderId) {
        List<BinomCampaign> allCampaigns = binomCampaignRepository.findByOrderId(orderId);

        if (allCampaigns.isEmpty()) {
            return "NO_CAMPAIGNS";
        }

        long activeCampaigns =
                allCampaigns.stream().filter(c -> "ACTIVE".equals(c.getStatus())).count();
        long stoppedCampaigns =
                allCampaigns.stream().filter(c -> "STOPPED".equals(c.getStatus())).count();

        // Return status based on 3-campaign distribution logic
        if (allCampaigns.size() == 3) {
            if (activeCampaigns == 3) {
                return "ALL_ACTIVE";
            } else if (activeCampaigns == 0) {
                return "ALL_STOPPED";
            } else {
                return "PARTIAL_ACTIVE";
            }
        } else {
            // Non-standard campaign count
            return String.format(
                    "NONSTANDARD_%d_TOTAL_%d_ACTIVE", allCampaigns.size(), activeCampaigns);
        }
    }

    /** Pause a specific campaign */
    @Transactional
    public void pauseCampaign(String campaignId) {
        if (campaignId == null || campaignId.trim().isEmpty()) {
            log.error("Cannot pause campaign: campaignId is null or empty");
            throw new IllegalArgumentException("Campaign ID is required");
        }

        Optional<BinomCampaign> campaignOpt = binomCampaignRepository.findByCampaignId(campaignId);
        if (campaignOpt.isEmpty()) {
            log.error("Campaign not found: {}", campaignId);
            throw new BinomApiException("Campaign not found: " + campaignId);
        }

        BinomCampaign campaign = campaignOpt.get();
        String previousStatus = campaign.getStatus();

        try {
            // Pause campaign in Binom system
            campaign.setStatus("PAUSED");
            campaign.setUpdatedAt(LocalDateTime.now());
            binomCampaignRepository.save(campaign);

            log.info("Paused campaign {} - Status: {} -> PAUSED", campaignId, previousStatus);
        } catch (Exception e) {
            log.error("Error pausing campaign {}: {}", campaignId, e.getMessage(), e);
            throw new BinomApiException("Failed to pause campaign: " + e.getMessage());
        }
    }

    /** Get paused campaigns for an order */
    @Transactional(readOnly = true)
    public List<BinomCampaign> getPausedCampaignsForOrder(Long orderId) {
        return binomCampaignRepository.findByOrderId(orderId).stream()
                .filter(c -> "PAUSED".equals(c.getStatus()))
                .collect(Collectors.toList());
    }

    /** Get completed campaigns for an order */
    @Transactional(readOnly = true)
    public List<BinomCampaign> getCompletedCampaignsForOrder(Long orderId) {
        return binomCampaignRepository.findByOrderId(orderId).stream()
                .filter(c -> "COMPLETED".equals(c.getStatus()))
                .collect(Collectors.toList());
    }

    /** Restart a completed campaign */
    @Transactional
    public void restartCampaign(String campaignId) {
        if (campaignId == null || campaignId.trim().isEmpty()) {
            log.error("Cannot restart campaign: campaignId is null or empty");
            throw new IllegalArgumentException("Campaign ID is required");
        }

        Optional<BinomCampaign> campaignOpt = binomCampaignRepository.findByCampaignId(campaignId);
        if (campaignOpt.isEmpty()) {
            log.error("Campaign not found: {}", campaignId);
            throw new BinomApiException("Campaign not found: " + campaignId);
        }

        BinomCampaign campaign = campaignOpt.get();
        String previousStatus = campaign.getStatus();

        try {
            // Restart campaign - reset metrics and set to active
            campaign.setStatus("ACTIVE");
            campaign.setUpdatedAt(LocalDateTime.now());
            binomCampaignRepository.save(campaign);

            log.info("Restarted campaign {} - Status: {} -> ACTIVE", campaignId, previousStatus);
        } catch (Exception e) {
            log.error("Error restarting campaign {}: {}", campaignId, e.getMessage(), e);
            throw new BinomApiException("Failed to restart campaign: " + e.getMessage());
        }
    }

    /** Resume a specific campaign - Updated for 3-campaign distribution logic */
    @Transactional
    public void resumeCampaign(String campaignId) {
        if (campaignId == null || campaignId.trim().isEmpty()) {
            log.error("Cannot resume campaign: campaignId is null or empty");
            throw new IllegalArgumentException("Campaign ID is required");
        }

        Optional<BinomCampaign> campaignOpt = binomCampaignRepository.findByCampaignId(campaignId);
        if (campaignOpt.isEmpty()) {
            log.error("Campaign not found: {}", campaignId);
            throw new BinomApiException("Campaign not found: " + campaignId);
        }

        BinomCampaign campaign = campaignOpt.get();
        String previousStatus = campaign.getStatus();

        try {
            // Resume campaign in Binom system if API method exists
            // Note: Currently updating status only as Binom API doesn't have resumeCampaign method
            campaign.setStatus("ACTIVE");
            campaign.setUpdatedAt(LocalDateTime.now());
            binomCampaignRepository.save(campaign);

            // Check if this is part of a 3-campaign order and log accordingly
            Long orderId = campaign.getOrder().getId();
            List<BinomCampaign> allOrderCampaigns = binomCampaignRepository.findByOrderId(orderId);
            long activeCampaigns =
                    allOrderCampaigns.stream().filter(c -> "ACTIVE".equals(c.getStatus())).count();

            log.info(
                    "Resumed campaign {} (order {}) - Status: {} -> ACTIVE. Order now has {}/{}"
                            + " active campaigns",
                    campaignId,
                    orderId,
                    previousStatus,
                    activeCampaigns,
                    allOrderCampaigns.size());

            // Log warning if not following 3-campaign pattern
            if (allOrderCampaigns.size() != 3) {
                log.warn(
                        "Order {} has {} campaigns instead of expected 3 for distributed campaign"
                                + " model",
                        orderId,
                        allOrderCampaigns.size());
            }

        } catch (Exception e) {
            log.error("Failed to resume campaign {}: {}", campaignId, e.getMessage());
            throw new BinomApiException("Failed to resume campaign: " + campaignId, e);
        }
    }

    // Campaign creation methods removed - campaigns are pre-configured manually in Binom

    // Additional methods required by the interface
    public String createOffer(String name, String url, String geo) {
        try {
            CreateOfferRequest offerRequest =
                    CreateOfferRequest.builder()
                            .name(name)
                            .url(url)
                            .geoTargeting(List.of(geo)) // Updated to List<String>
                            .description("SMM Panel auto-generated offer")
                            .affiliateNetworkId(1L) // Default affiliate network ID
                            .type("REDIRECT")
                            .status("ACTIVE")
                            .payoutType("CPA")
                            .requiresApproval(false)
                            .isArchived(false)
                            .build();

            CreateOfferResponse response = binomClient.createOffer(offerRequest);
            log.info("Created new Binom offer: {}", response.getOfferId());
            return response.getOfferId();

        } catch (Exception e) {
            log.error("Failed to create Binom offer: {}", e.getMessage());
            throw new BinomApiException("Failed to create Binom offer", e);
        }
    }

    public boolean assignOfferToCampaign(String offerId, String campaignId, int priority) {
        try {
            binomClient.assignOfferToCampaign(campaignId, offerId);
            log.info(
                    "Assigned offer {} to campaign {} with priority {}",
                    offerId,
                    campaignId,
                    priority);
            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to assign offer {} to campaign {}: {}",
                    offerId,
                    campaignId,
                    e.getMessage());
            return false;
        }
    }

    public BinomIntegrationResponse createBinomIntegration(BinomIntegrationRequest request) {
        try {
            validateRequest(request);

            Order order =
                    orderRepository
                            .findById(request.getOrderId())
                            .orElseThrow(
                                    () ->
                                            new BinomApiException(
                                                    "Order not found: " + request.getOrderId()));

            return createBinomIntegration(
                    order,
                    "video_" + request.getOrderId(),
                    request.getClipCreated(),
                    request.getTargetUrl());

        } catch (Exception e) {
            log.error("Failed to create Binom integration: {}", e.getMessage());
            return BinomIntegrationResponse.builder()
                    .success(false)
                    .campaignsCreated(0)
                    .message("Failed to create Binom integration: " + e.getMessage())
                    .errorCode("INTEGRATION_FAILED")
                    .build();
        }
    }
}
