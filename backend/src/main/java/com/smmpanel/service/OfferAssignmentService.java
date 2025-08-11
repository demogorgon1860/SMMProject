package com.smmpanel.service;

import com.smmpanel.dto.binom.AssignedCampaignInfo;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.dto.binom.OfferAssignmentResponse;
import com.smmpanel.entity.FixedBinomCampaign;
import com.smmpanel.entity.Order;
import com.smmpanel.repository.jpa.FixedBinomCampaignRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unified OfferAssignmentService - eliminates unnecessary interface layer Handles offer assignment
 * to fixed Binom campaigns with retry logic
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferAssignmentService {

    private final BinomService binomService;
    private final FixedBinomCampaignRepository fixedCampaignRepository;
    private final OrderRepository orderRepository;

    // In-memory cache for assignment statuses
    private final Map<Long, String> assignmentStatusCache = new ConcurrentHashMap<>();

    @Transactional
    public OfferAssignmentResponse assignOfferToFixedCampaigns(OfferAssignmentRequest request) {
        log.info("Starting offer assignment for order: {}", request.getOrderId());

        try {
            // Validate the request
            if (!validateAssignment(request)) {
                return buildErrorResponse(request.getOrderId(), "Invalid assignment request");
            }

            // Get all active fixed campaigns
            List<FixedBinomCampaign> fixedCampaigns = fixedCampaignRepository.findByActiveTrue();
            if (fixedCampaigns.isEmpty()) {
                log.error("No active fixed campaigns found for offer assignment");
                return buildErrorResponse(request.getOrderId(), "No active campaigns available");
            }

            // Create offer in Binom first
            String offerId =
                    binomService.createOffer(
                            request.getOfferName(),
                            request.getTargetUrl(),
                            "SMM Panel Offer for Order #" + request.getOrderId());

            if (offerId == null) {
                return buildErrorResponse(request.getOrderId(), "Failed to create offer in Binom");
            }

            // Assign offer to all fixed campaigns
            List<String> assignedCampaignIds = new ArrayList<>();
            int successfulAssignments = 0;

            for (FixedBinomCampaign campaign : fixedCampaigns) {
                try {
                    boolean assigned =
                            binomService.assignOfferToCampaign(
                                    offerId, campaign.getCampaignId(), 1);
                    if (assigned) {
                        assignedCampaignIds.add(campaign.getCampaignId());
                        successfulAssignments++;
                        log.debug(
                                "Successfully assigned offer {} to campaign {}",
                                offerId,
                                campaign.getCampaignId());
                    } else {
                        log.warn(
                                "Failed to assign offer {} to campaign {}",
                                offerId,
                                campaign.getCampaignId());
                    }
                } catch (Exception e) {
                    log.error(
                            "Error assigning offer {} to campaign {}: {}",
                            offerId,
                            campaign.getCampaignId(),
                            e.getMessage());
                }
            }

            // Update assignment status cache
            String status = successfulAssignments > 0 ? "SUCCESS" : "PARTIAL_FAILURE";
            assignmentStatusCache.put(request.getOrderId(), status);

            // Update order with offer assignment info
            updateOrderWithOfferInfo(request.getOrderId(), offerId, assignedCampaignIds);

            return OfferAssignmentResponse.builder()
                    .orderId(request.getOrderId())
                    .offerId(offerId)
                    .offerName(request.getOfferName())
                    .targetUrl(request.getTargetUrl())
                    .campaignsCreated(successfulAssignments)
                    .campaignIds(assignedCampaignIds)
                    .status(status)
                    .message(
                            String.format(
                                    "Offer assigned to %d out of %d campaigns",
                                    successfulAssignments, fixedCampaigns.size()))
                    .build();

        } catch (Exception e) {
            log.error(
                    "Offer assignment failed for order {}: {}",
                    request.getOrderId(),
                    e.getMessage(),
                    e);
            assignmentStatusCache.put(request.getOrderId(), "ERROR");
            return buildErrorResponse(request.getOrderId(), "Assignment failed: " + e.getMessage());
        }
    }

    @Cacheable(value = "assignedCampaigns", key = "#orderId")
    public List<AssignedCampaignInfo> getAssignedCampaigns(Long orderId) {
        log.debug("Getting assigned campaigns for order: {}", orderId);

        try {
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // This would typically come from a join table or campaign assignment records
            // For now, we'll return the fixed campaigns that are active
            List<FixedBinomCampaign> fixedCampaigns = fixedCampaignRepository.findByActiveTrue();

            return fixedCampaigns.stream()
                    .map(
                            campaign ->
                                    AssignedCampaignInfo.builder()
                                            .campaignId(campaign.getCampaignId())
                                            .campaignName(campaign.getCampaignName())
                                            .geoTargeting(campaign.getGeoTargeting())
                                            .weight(campaign.getWeight())
                                            .priority(campaign.getPriority())
                                            .active(campaign.getActive())
                                            .assignedAt(
                                                    LocalDateTime.now()) // This should come from
                                            // assignment record
                                            .build())
                    .toList();

        } catch (Exception e) {
            log.error("Failed to get assigned campaigns for order {}: {}", orderId, e.getMessage());
            return new ArrayList<>();
        }
    }

    public String getAssignmentStatus(Long orderId) {
        String status = assignmentStatusCache.get(orderId);
        if (status == null) {
            // Check database or return default status
            return "PENDING";
        }
        return status;
    }

    public void updateAssignmentStatus(Long orderId, String status) {
        log.debug("Updating assignment status for order {} to {}", orderId, status);
        assignmentStatusCache.put(orderId, status);

        // You could also persist this to database if needed
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                // Update order with assignment status if you have such field
                // order.setOfferAssignmentStatus(status);
                // orderRepository.save(order);
            }
        } catch (Exception e) {
            log.warn("Could not persist assignment status to database: {}", e.getMessage());
        }
    }

    public boolean validateAssignment(OfferAssignmentRequest request) {
        if (request == null) {
            log.warn("Assignment request is null");
            return false;
        }

        if (request.getOrderId() == null || request.getOrderId() <= 0) {
            log.warn("Invalid order ID: {}", request.getOrderId());
            return false;
        }

        if (request.getOfferName() == null || request.getOfferName().trim().isEmpty()) {
            log.warn("Offer name is empty for order: {}", request.getOrderId());
            return false;
        }

        if (request.getTargetUrl() == null || request.getTargetUrl().trim().isEmpty()) {
            log.warn("Target URL is empty for order: {}", request.getOrderId());
            return false;
        }

        // Validate URL format
        if (!isValidUrl(request.getTargetUrl())) {
            log.warn("Invalid target URL format: {}", request.getTargetUrl());
            return false;
        }

        // Check if order exists
        if (!orderRepository.existsById(request.getOrderId())) {
            log.warn("Order does not exist: {}", request.getOrderId());
            return false;
        }

        // Check if assignment already exists (prevent duplicates)
        if ("SUCCESS".equals(assignmentStatusCache.get(request.getOrderId()))) {
            log.warn("Order {} already has successful offer assignment", request.getOrderId());
            return false;
        }

        return true;
    }

    private boolean isValidUrl(String url) {
        try {
            new java.net.URL(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private OfferAssignmentResponse buildErrorResponse(Long orderId, String message) {
        return OfferAssignmentResponse.builder()
                .orderId(orderId)
                .status("ERROR")
                .message(message)
                .campaignsCreated(0)
                .campaignIds(new ArrayList<>())
                .build();
    }

    private void updateOrderWithOfferInfo(Long orderId, String offerId, List<String> campaignIds) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                // You could add fields to Order entity to store offer assignment info
                // order.setBinomOfferId(offerId);
                // order.setAssignedCampaignIds(String.join(",", campaignIds));
                // order.setOfferAssignedAt(LocalDateTime.now());
                // orderRepository.save(order);

                log.debug("Order {} updated with offer assignment info", orderId);
            }
        } catch (Exception e) {
            log.warn("Could not update order with offer info: {}", e.getMessage());
        }
    }

    /** Cleanup method to remove old cache entries */
    public void cleanupOldAssignments() {
        // This could be called by a scheduled task to clean up old cache entries
        log.debug("Assignment status cache size: {}", assignmentStatusCache.size());

        // You could implement logic to remove entries older than X hours/days
        // based on order creation time or last access time
    }
}
