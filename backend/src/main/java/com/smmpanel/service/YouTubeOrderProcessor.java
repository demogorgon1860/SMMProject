package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.YouTubeApiException;
import com.smmpanel.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * YouTube Order Processor
 * Handles YouTube-specific order processing asynchronously
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeOrderProcessor {

    private final OrderRepository orderRepository;
    private final YouTubeApiService youTubeApiService;
    private final BinomService binomService;

    /**
     * Process YouTube order asynchronously
     */
    @Async
    @EventListener
    @Transactional
    public void processOrder(OrderCreatedEvent event) {
        log.info("Processing YouTube order: orderId={}, userId={}", event.getOrderId(), event.getUserId());
        
        try {
            Optional<Order> orderOpt = orderRepository.findById(event.getOrderId());
            if (orderOpt.isEmpty()) {
                log.error("Order not found: orderId={}", event.getOrderId());
                return;
            }
            
            Order order = orderOpt.get();
            
            // Step 1: Verify video exists
            if (!isYouTubeOrder(order)) {
                log.debug("Not a YouTube order, skipping: orderId={}", order.getId());
                return;
            }
            
            // Step 2: Get and cache start_count
            processYouTubeVerification(order);
            
            // Step 3: Calculate required_clicks based on coefficient
            calculateRequiredClicks(order);
            
            // Step 4: Create Binom campaign
            createBinomCampaign(order);
            
            // Step 5: Update order status
            updateOrderStatus(order, OrderStatus.ACTIVE);
            
            log.info("Successfully processed YouTube order: orderId={}", order.getId());
            
        } catch (Exception e) {
            log.error("Failed to process YouTube order: orderId={}", event.getOrderId(), e);
            handleProcessingError(event.getOrderId(), e);
        }
    }

    /**
     * Check if order is a YouTube order
     */
    private boolean isYouTubeOrder(Order order) {
        return order.getLink() != null && 
               (order.getLink().contains("youtube.com") || order.getLink().contains("youtu.be"));
    }

    /**
     * Process YouTube verification
     */
    private void processYouTubeVerification(Order order) {
        try {
            String videoId = youTubeApiService.extractVideoId(order.getLink());
            
            // Verify video exists and is public
            if (!youTubeApiService.verifyVideoExists(videoId)) {
                throw new YouTubeApiException("Video does not exist or is not public: " + videoId);
            }
            
            // Get current view count
            Long viewCount = youTubeApiService.getViewCount(videoId);
            
            // Update order with video details
            order.setYoutubeVideoId(videoId);
            order.setStartCount(viewCount.intValue());
            orderRepository.save(order);
            
            log.info("YouTube verification completed: orderId={}, videoId={}, startCount={}", 
                    order.getId(), videoId, viewCount);
                    
        } catch (Exception e) {
            log.error("YouTube verification failed: orderId={}", order.getId(), e);
            throw new YouTubeApiException("YouTube verification failed for order: " + order.getId(), e);
        }
    }

    /**
     * Calculate required clicks based on coefficient
     */
    private void calculateRequiredClicks(Order order) {
        try {
            BigDecimal coefficient = order.getCoefficient();
            if (coefficient == null) {
                // Default coefficient based on service type
                coefficient = determineDefaultCoefficient(order);
                order.setCoefficient(coefficient);
            }
            
            // Calculate required clicks
            int targetViews = order.getQuantity();
            int requiredClicks = calculateRequiredClicks(targetViews, coefficient);
            
            // Update order with calculated values
            order.setTargetViews(requiredClicks);
            orderRepository.save(order);
            
            log.info("Calculated required clicks: orderId={}, targetViews={}, coefficient={}, requiredClicks={}", 
                    order.getId(), targetViews, coefficient, requiredClicks);
                    
        } catch (Exception e) {
            log.error("Failed to calculate required clicks: orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to calculate required clicks", e);
        }
    }

    /**
     * Determine default coefficient based on service type
     */
    private BigDecimal determineDefaultCoefficient(Order order) {
        // Default coefficient logic - can be enhanced based on service configuration
        if (order.getService() != null && order.getService().getName() != null) {
            String serviceName = order.getService().getName().toLowerCase();
            if (serviceName.contains("clip")) {
                return new BigDecimal("3.0"); // Clip creation coefficient
            }
        }
        return new BigDecimal("4.0"); // Default no-clip coefficient
    }

    /**
     * Calculate required clicks based on target views and coefficient
     */
    private int calculateRequiredClicks(int targetViews, BigDecimal coefficient) {
        BigDecimal requiredClicks = new BigDecimal(targetViews).multiply(coefficient);
        return requiredClicks.setScale(0, BigDecimal.ROUND_UP).intValue();
    }

    /**
     * Create Binom campaign
     */
    private void createBinomCampaign(Order order) {
        try {
            // Determine if clip creation is needed
            boolean hasClip = order.getCoefficient().compareTo(new BigDecimal("3.0")) == 0;
            
            // Create Binom campaign
            binomService.createCampaign(order, order.getLink(), hasClip);
            
            log.info("Binom campaign created: orderId={}, hasClip={}", order.getId(), hasClip);
            
        } catch (Exception e) {
            log.error("Failed to create Binom campaign: orderId={}", order.getId(), e);
            throw new RuntimeException("Failed to create Binom campaign", e);
        }
    }

    /**
     * Update order status
     */
    private void updateOrderStatus(Order order, OrderStatus newStatus) {
        OrderStatus oldStatus = order.getStatus();
        order.setStatus(newStatus);
        orderRepository.save(order);
        
        log.info("Order status updated: orderId={}, oldStatus={}, newStatus={}", 
                order.getId(), oldStatus, newStatus);
    }

    /**
     * Handle processing error
     */
    private void handleProcessingError(Long orderId, Exception e) {
        try {
            Optional<Order> orderOpt = orderRepository.findById(orderId);
            if (orderOpt.isPresent()) {
                Order order = orderOpt.get();
                order.setErrorMessage("YouTube processing failed: " + e.getMessage());
                order.setStatus(OrderStatus.HOLDING);
                orderRepository.save(order);
                
                log.error("Order processing failed and marked as holding: orderId={}, error={}", 
                        orderId, e.getMessage());
            }
        } catch (Exception saveError) {
            log.error("Failed to update order error status: orderId={}", orderId, saveError);
        }
    }
} 