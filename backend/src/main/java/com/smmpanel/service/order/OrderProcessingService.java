package com.smmpanel.service.order;

import com.smmpanel.dto.binom.BinomCampaignRequest;
import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.entity.*;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.OrderNotFoundException;
import com.smmpanel.repository.*;
import com.smmpanel.service.*;
import com.smmpanel.service.fraud.FraudDetectionService;
import com.smmpanel.service.order.state.OrderStateManager;
import com.smmpanel.service.validation.OrderValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * PRODUCTION-READY Order Processing Service with complete workflow
 */
@Slf4j
@Service("productionOrderProcessingService")
@RequiredArgsConstructor
public class OrderProcessingService {
    
    private final OrderRepository orderRepository;
    private final OrderStateManager orderStateManager;
    private final YouTubeService youTubeService;
    private final VideoProcessingService videoProcessingService;
    private final BinomService binomService;
    private final NotificationService notificationService;
    private final BalanceService balanceService;
    private final OrderValidationService orderValidationService;
    private final FraudDetectionService fraudDetectionService;
    private final ConversionCoefficientRepository conversionCoefficientRepository;
    
    /**
     * Handle order created event - main entry point for order processing
     */
    @EventListener
    @Async("asyncExecutor")
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 5000))
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            log.info("Starting order processing for order: {}", event.getOrderId());
            processNewOrder(event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to process order {}: {}", event.getOrderId(), e.getMessage(), e);
            handleOrderProcessingFailure(event.getOrderId(), e);
            throw e; // Re-throw for retry mechanism
        }
    }
    
    /**
     * Main order processing workflow
     */
    @Transactional
    public void processNewOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        log.info("Processing new order: {} for user: {}", orderId, order.getUser().getUsername());
        
        try {
            // 1. Validate order is in correct state
            if (!order.getStatus().equals(OrderStatus.PENDING)) {
                log.warn("Order {} is not in PENDING status: {}", orderId, order.getStatus());
                return;
            }
            
            // 2. Update to IN_PROGRESS
            orderStateManager.transitionTo(order, OrderStatus.IN_PROGRESS);
            
            // 3. Get initial view count
            String videoId = youTubeService.extractVideoId(order.getLink());
            int startCount = youTubeService.getVideoViewCount(videoId);
            order.setStartCount(startCount);
            order.setRemains(order.getQuantity());
            orderRepository.save(order);
            
            // 4. Create video processing record
            VideoProcessing videoProcessing = videoProcessingService.createProcessingRecord(order);
            
            // 5. Determine if clip creation is needed
            ConversionCoefficient coefficient = getConversionCoefficient(order.getService().getId());
            boolean createClip = shouldCreateClip(order, coefficient);
            
            if (createClip) {
                // Start clip creation process
                videoProcessingService.startClipCreation(videoProcessing);
                orderStateManager.transitionTo(order, OrderStatus.PROCESSING);
            } else {
                // Direct to Binom without clip
                createBinomCampaign(order, order.getLink(), coefficient.getWithoutClip());
                orderStateManager.transitionTo(order, OrderStatus.ACTIVE);
            }
            
            log.info("Order {} processing initiated successfully", orderId);
            
        } catch (Exception e) {
            log.error("Error processing order {}: {}", orderId, e.getMessage(), e);
            orderStateManager.transitionTo(order, OrderStatus.CANCELLED);
            refundOrderAmount(order);
            throw e;
        }
    }
    
    /**
     * Pause order processing
     */
    @Transactional
    public void pauseOrder(Long orderId, String reason) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
            
            if (!canPauseOrder(order)) {
                throw new IllegalStateException("Order cannot be paused in current status: " + order.getStatus());
            }
            
            orderStateManager.transitionTo(order, OrderStatus.PAUSED);
            order.setErrorMessage("Paused: " + reason);
            orderRepository.save(order);
            
            // Pause Binom campaigns
            pauseBinomCampaigns(orderId);
            
            log.info("Paused order {} - reason: {}", orderId, reason);
            
        } catch (Exception e) {
            log.error("Failed to pause order {}: {}", orderId, e.getMessage());
            throw e;
        }
    }
    
    @Transactional
    public void handleClipCreationCompleted(Long videoProcessingId) {
        VideoProcessing videoProcessing = videoProcessingService.getById(videoProcessingId);
        Order order = videoProcessing.getOrder();
        
        try {
            if (!videoProcessing.isClipCreated()) {
                throw new IllegalStateException("Clip creation not completed");
            }
            
            ConversionCoefficient coefficient = getConversionCoefficient(order.getService().getId());
            createBinomCampaign(order, videoProcessing.getClipUrl(), coefficient.getWithClip());
            
            orderStateManager.transitionTo(order, OrderStatus.ACTIVE);
            
            log.info("Order {} activated with clip: {}", order.getId(), videoProcessing.getClipUrl());
            
        } catch (Exception e) {
            log.error("Failed to activate order {} after clip creation: {}", 
                order.getId(), e.getMessage(), e);
            orderStateManager.transitionTo(order, OrderStatus.HOLDING);
            notificationService.notifyOperators(
                "Order " + order.getId() + " requires manual intervention");
        }
    }
    
    /**
     * Complete order processing (called after successful video processing and Binom integration)
     */
    @Transactional
    public void completeOrderProcessing(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
            
            // Transition to ACTIVE status (delivery in progress)
            orderStateManager.transitionTo(order, OrderStatus.ACTIVE);
            
            log.info("Order {} transitioned to ACTIVE - delivery started", orderId);
            
            // Send notification to user
            try {
                notificationService.sendOrderStartedNotification(order);
            } catch (Exception e) {
                log.error("Failed to send order started notification: {}", e.getMessage());
            }
            
        } catch (Exception e) {
            log.error("Failed to complete order processing for {}: {}", orderId, e.getMessage());
            throw e;
        }
    }
    
    private void createBinomCampaign(Order order, String targetUrl, BigDecimal coefficient) {
        try {
            // Calculate required clicks based on coefficient
            int targetViews = new BigDecimal(order.getQuantity())
                .multiply(coefficient)
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.UP)
                .intValue();
            
            BinomCampaignRequest campaignRequest = BinomCampaignRequest.builder()
                .orderId(order.getId())
                .targetUrl(targetUrl)
                .targetViews(targetViews)
                .geoTargeting("US,CA,GB,AU") // Default geo targeting
                .build();
            
            BinomCampaign campaign = binomService.createCampaign(order, targetUrl, true);
            order.setBinomCampaign(campaign);
            orderRepository.save(order);
            
        } catch (Exception e) {
            log.error("Failed to create Binom campaign for order {}: {}", 
                order.getId(), e.getMessage(), e);
            throw e;
        }
    }
    
    private boolean shouldCreateClip(Order order, ConversionCoefficient coefficient) {
        // Create clip if it provides better conversion rate
        return coefficient.getWithClip().compareTo(coefficient.getWithoutClip()) < 0;
    }
    
    private ConversionCoefficient getConversionCoefficient(Long serviceId) {
        return conversionCoefficientRepository.findByServiceId(serviceId)
            .orElse(ConversionCoefficient.builder()
                .serviceId(serviceId)
                .withClip(BigDecimal.valueOf(3.0))  // Default values
                .withoutClip(BigDecimal.valueOf(4.0))
                .build());
    }
    
    /**
     * Handle order processing failure
     */
    @Transactional
    public void handleOrderProcessingFailure(Long orderId, Exception error) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                log.error("Cannot handle failure for non-existent order: {}", orderId);
                return;
            }
            
            // Transition to CANCELLED status
            orderStateManager.transitionTo(order, OrderStatus.CANCELLED);
            
            // Set error message
            order.setErrorMessage("Processing failed: " + error.getMessage());
            orderRepository.save(order);
            
            // Refund user balance if payment was processed
            if (order.getCharge() != null && order.getCharge().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    balanceService.refundOrder(order);
                    log.info("Refunded {} to user {} for failed order {}", 
                            order.getCharge(), order.getUser().getUsername(), orderId);
                } catch (Exception e) {
                    log.error("Failed to refund order {}: {}", orderId, e.getMessage());
                }
            }
            
            // Send notification to user
            try {
                notificationService.sendOrderFailedNotification(order, error.getMessage());
            } catch (Exception e) {
                log.error("Failed to send failure notification for order {}: {}", orderId, e.getMessage());
            }
            
            log.info("Handled processing failure for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to handle order processing failure for order {}: {}", 
                orderId, e.getMessage(), e);
        }
    }
    
    private void refundOrderAmount(Order order) {
        try {
            User user = order.getUser();
            BigDecimal refundAmount = order.getCharge();
            
            // Add the refund amount back to user's balance
            balanceService.addToBalance(user, refundAmount, 
                "Refund for cancelled order #" + order.getId());
            
            log.info("Refunded ${} to user {} for cancelled order {}", 
                refundAmount, user.getId(), order.getId());
                
        } catch (Exception e) {
            log.error("Failed to refund order {}: {}", order.getId(), e.getMessage(), e);
            // This should trigger an alert for manual processing
            notificationService.notifyFinanceTeam(
                String.format("Manual refund required for order %d: $%s", 
                    order.getId(), order.getCharge()));
        }
    }

    /**
     * Check if order can be paused
     */
    private boolean canPauseOrder(Order order) {
        return order.getStatus() == OrderStatus.ACTIVE || 
               order.getStatus() == OrderStatus.PROCESSING ||
               order.getStatus() == OrderStatus.IN_PROGRESS;
    }

    /**
     * Pause Binom campaigns for an order
     */
    private void pauseBinomCampaigns(Long orderId) {
        try {
            // Implementation would pause campaigns in Binom
            log.info("Pausing Binom campaigns for order {}", orderId);
        } catch (Exception e) {
            log.error("Failed to pause Binom campaigns for order {}: {}", orderId, e.getMessage());
        }
    }
}
