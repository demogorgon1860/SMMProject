package com.smmpanel.service.order;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.entity.*;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.OrderNotFoundException;
import com.smmpanel.repository.*;
import com.smmpanel.service.BalanceService;
import com.smmpanel.service.YouTubeService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderProcessingService {
    
    private final OrderRepository orderRepository;
    private final OrderStateManager orderStateManager;
    private final YouTubeService youTubeService;
    private final BinomIntegrationService binomIntegrationService;
    private final VideoProcessingService videoProcessingService;
    private final NotificationService notificationService;
    private final BalanceService balanceService;
    private final OrderValidationService orderValidationService;
    private final FraudDetectionService fraudDetectionService;
    private final ConversionCoefficientRepository conversionCoefficientRepository;
    
    @EventListener
    @Async
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 5000))
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            processNewOrder(event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to process order {}: {}", event.getOrderId(), e.getMessage(), e);
            handleOrderProcessingFailure(event.getOrderId(), e);
            throw e; // Re-throw for retry mechanism
        }
    }
    
    @Transactional
    public void processNewOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        log.info("Processing new order: {}", orderId);
        
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
    
    private void createBinomCampaign(Order order, String targetUrl, BigDecimal coefficient) {
        try {
            // Calculate required clicks based on coefficient
            int requiredClicks = order.getQuantity().multiply(coefficient)
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.UP)
                .intValue();
            
            BinomCampaignRequest campaignRequest = BinomCampaignRequest.builder()
                .orderId(order.getId())
                .targetUrl(targetUrl)
                .requiredClicks(requiredClicks)
                .geoTargeting("US,CA,GB,AU") // Default geo targeting
                .build();
            
            BinomCampaign campaign = binomIntegrationService.createCampaign(campaignRequest);
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
    
    private void handleOrderProcessingFailure(Long orderId, Exception error) {
        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setErrorMessage(error.getMessage());
                orderStateManager.transitionTo(order, OrderStatus.HOLDING);
                
                // Notify operators for manual intervention
                notificationService.notifyOperators(
                    String.format("Order %d failed processing: %s", orderId, error.getMessage()));
            }
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
}
